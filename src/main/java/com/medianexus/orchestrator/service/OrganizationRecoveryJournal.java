package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.mapper.OrganizationJournalMapper;
import com.medianexus.orchestrator.mapper.OrganizationJournalMapper.Row;
import com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer;
import com.medianexus.orchestrator.service.organization.DurableLibraryOrganizer.*;
import com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Persistent write authority has no expiring lease. Foreign-process journals can only be observed. */
@Service
public class OrganizationRecoveryJournal {
    private final OrganizationJournalMapper mapper;
    private final ObjectMapper json;
    private final OfflineIngestScheduler scheduler;
    private final OpenListClient openList;
    private final OrganizationRecoveryAcceptance acceptance;
    private final String process = processIdentity();
    private final Set<String> active = new HashSet<>();
    private volatile boolean ready;
    private volatile boolean stopping;
    private final DurableLibraryOrganizer organizer;
    private String afterType = "";
    private String afterId = "";

    public OrganizationRecoveryJournal(OrganizationJournalMapper mapper, ObjectMapper json,
            OfflineIngestScheduler scheduler, OpenListClient openList,
            @org.springframework.beans.factory.annotation.Qualifier("cloudDrive2LibraryOrganizer")
            com.medianexus.orchestrator.integration.clouddrive.CloudDrive2LibraryOrganizer organizer,
            OrganizationRecoveryAcceptance acceptance) {
        this.mapper = mapper; this.json = json; this.scheduler = scheduler;
        this.openList = openList; this.organizer = organizer; this.acceptance = acceptance;
    }

    @PostConstruct void initialize() { mapper.createTable(); }

    public record IdentityRepair(com.medianexus.orchestrator.integration.emby.EmbyMovieIdentitySnapshot previous,
                                 Set<String> paths, int expectedTmdbId, String candidateId, String sentAt) {}

    public record Payload(Snapshot snapshot, Map<String, ActionState> actions, String hash,
                          OpenListClient.OfflineDirectory directory, int organized, int skipped, int videos,
                          int tmdbId, Integer season, boolean refreshSent, FrozenTaskEvidence taskEvidence,
                          IdentityRepair identityRepair) {
        public Payload(Snapshot snapshot, Map<String, ActionState> actions, String hash, OpenListClient.OfflineDirectory directory,
                       int organized, int skipped, int videos, int tmdbId, Integer season, boolean refreshSent, FrozenTaskEvidence evidence) {
            this(snapshot, actions, hash, directory, organized, skipped, videos, tmdbId, season, refreshSent, evidence, null);
        }
        Payload withRefreshSent(boolean sent) {
            return new Payload(snapshot, actions, hash, directory, organized, skipped, videos, tmdbId, season, sent, taskEvidence, identityRepair);
        }
        Payload withIdentityRepair(IdentityRepair repair) {
            return new Payload(snapshot, actions, hash, directory, organized, skipped, videos, tmdbId, season, refreshSent, taskEvidence, repair);
        }
    }
    public record RecoveryStatus(String taskType, String taskId, String phase, String status,
                                 int attempt, long nextCheck, String lastError, int organized, int skipped, int videos) {
        public RecoveryStatus(String type, String id, String phase, String status, int attempt, long next, String error) {
            this(type, id, phase, status, attempt, next, error, 0, 0, 0);
        }
    }

    public record FrozenTaskEvidence(String title, String originalTitle, Integer year, int tmdbId, Integer season,
                                     String releaseTitle, String releaseFileEvidence, String originalStatus) {
        public FrozenTaskEvidence(String title, String originalTitle, Integer year, int tmdbId, Integer season,
                                  String releaseTitle, String releaseFileEvidence) {
            this(title, originalTitle, year, tmdbId, season, releaseTitle, releaseFileEvidence, null);
        }
    }

    public RecoveryStatus status(String type, String id) {
        Row row = mapper.get(type.toLowerCase(Locale.ROOT), id);
        if (row == null) return null;
        Payload payload = decode(row.payload());
        boolean manual = !process.equals(row.ownerToken()) && !priorProcessGone(row.ownerToken()) && !row.status().equals("COMPLETE");
        return new RecoveryStatus(row.taskType(), id, row.phase(), manual ? "MANUAL_CHECK" : row.status(), row.attempt(), row.nextCheck(),
                manual ? "OWNER_NOT_RETIRED" : row.lastError(),
                payload.organized(), payload.skipped(), payload.videos());
    }

    public void refreshAfterOrganization(String type, String id) {
        String owner = type + ":" + id;
        synchronized (active) {
            if (stopping || !active.add(owner)) return;
        }
        try {
            try {
                Row row = mapper.get(type, id);
                if (row == null) throw new IllegalStateException("整理日志不存在");
                Session session = new Session(row, decode(row.payload()));
                try { session.accept(); }
                catch (RuntimeException pending) { session.waiting(pending); }
            } catch (RuntimeException unavailable) {
                // FILES is already committed. DB/acceptance failure is journal-owned, never a failed download callback.
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("整理验收检查点待恢复；未报告原任务成功");
            }
        } finally { synchronized (active) { active.remove(owner); } }
    }

    /** Called inside the existing organization lane, before the first rename or move. */
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void organize(String type, String id, LibraryOrganizationPlan plan, int organized, int skipped,
                         int videos, int tmdbId, Integer season, String hash, FrozenTaskEvidence taskEvidence) {
        String owner = type + ":" + id;
        synchronized (active) {
            if (stopping) throw new IllegalStateException("整理执行者已停止接收写入");
            if (!active.add(owner)) throw new IllegalStateException("整理日志已有活动执行者");
        }
        try {
            acceptance.validateAdoptionTarget(type, plan.targetDirectory(), tmdbId, season);
            Snapshot snapshot = organizer.capture(plan);
            var directory = openList.offlineDirectoryForPath(plan.targetDirectory());
            if (directory.account().equals("unknown") || directory.rootId().isBlank())
                throw new IllegalStateException("持久整理需要已核实账号和根目录身份");
            Payload payload = new Payload(snapshot, new LinkedHashMap<>(), hash, directory,
                    organized, skipped, videos, tmdbId, season, false, taskEvidence);
            if (taskEvidence != null && "SUCCEEDED".equals(taskEvidence.originalStatus())
                    && (!type.equals("movie") || !plan.renames().isEmpty() || !plan.moves().isEmpty() || !plan.deletions().isEmpty()))
                throw new IllegalStateException("历史成功任务仅允许零文件变更的身份验收");
            acceptance.validateCapturedTarget(type, payload);
            if (mapper.insert(type, id, encode(payload), process) != 1) throw new IllegalStateException("整理计划未持久化");
            // Retain before mutation, including when a later error checkpoint cannot reach the database.
            scheduler.retainOrganizationRecovery(owner, directory, hash);
            Session session = new Session(mapper.get(type, id), payload);
            try { session.files(); }
            catch (RuntimeException failure) { session.waiting(failure); throw failure; }
        } finally { synchronized (active) { active.remove(owner); } }
    }

    @EventListener(ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void startupGuardInstalled() {
        // Install every exclusion, including permanently isolated rows, before enabling the dispatcher.
        for (Row row : mapper.unfinished()) {
            Payload payload = decode(row.payload());
            scheduler.retainOrganizationRecovery(row.taskType() + ":" + row.taskId(),
                    payload.directory(), payload.hash());
        }
        ready = true;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public synchronized void dispatch() {
        if (stopping || !ready || scheduler.submissionPauseReason() != null) return;
        try {
            List<Row> page = mapper.due(System.currentTimeMillis(), afterType, afterId);
            if (page.isEmpty()) { afterType = ""; afterId = ""; return; }
            for (Row row : page) {
                // Advance even for foreign/blocked owners without modifying their journal checkpoints.
                afterType = row.taskType(); afterId = row.taskId();
                String owner = row.taskType() + ":" + row.taskId();
                synchronized (active) { if (active.contains(owner)) continue; }
                Payload payload = decode(row.payload());
                try {
                    scheduler.enqueueOrganizationRecovery(owner, row.taskId(), payload.snapshot().plan().targetDirectory(),
                            payload.hash(), () -> recover(row.taskType(), row.taskId()));
                } catch (java.util.concurrent.RejectedExecutionException excluded) {
                    // Claims remain; a conflict never creates original-task success or consumes a download slot.
                }
            }
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("整理恢复日志暂不可读；本轮未授权写入");
        }
    }

    /** Spring destroys this dependent before scheduler/CD2 clients. No shutdown lease or cloud write is involved. */
    @jakarta.annotation.PreDestroy
    public void retireAfterQuiescence() {
        synchronized (active) { stopping = true; ready = false; }
        if (!scheduler.quiesceOrganizationRecovery()) return;
        synchronized (active) { if (!active.isEmpty()) return; }
        try {
            // Avoid querying a disabled CD2 integration when this process owns no durable work.
            if (mapper.unfinished().stream().noneMatch(row -> process.equals(row.ownerToken()))) return;
            openList.requireOrganizationQueuesQuiet();
            organizer.requireRecoveryWritersQuiet();
            mapper.retireOwner(process);
        } catch (RuntimeException uncertain) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("整理执行者未取得安全退役证明；下次启动仅允许只读核对");
        }
    }

    private static String processIdentity() {
        try {
            String kernel = java.nio.file.Files.readString(java.nio.file.Path.of("/proc/sys/kernel/random/boot_id"))
                    + java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/ns/pid"));
            String namespace = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(kernel.getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
            return namespace + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException unavailable) { return UUID.randomUUID().toString(); }
    }

    private boolean priorProcessGone(String owner) {
        if (owner.startsWith("RETIRED:")) return true;
        String[] prior = owner.split(":", -1);
        String[] current = process.split(":", -1);
        if (prior.length != 3 || current.length != 3 || !prior[0].equals(current[0])) return false;
        try { return ProcessHandle.of(Long.parseLong(prior[1])).isEmpty(); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void recover(String type, String id) {
        String owner = type + ":" + id;
        synchronized (active) { if (!active.add(owner)) return; }
        if (stopping) { synchronized (active) { active.remove(owner); } return; }
        try {
            Row row = mapper.get(type, id);
            if (row == null || Set.of("COMPLETE", "ISOLATED").contains(row.status())) return;
            if (!process.equals(row.ownerToken()) && priorProcessGone(row.ownerToken())) {
                openList.requireOrganizationQueuesQuiet();
                organizer.requireRecoveryWritersQuiet();
                if (mapper.claim(row, process) != 1) return;
                row = mapper.get(type, id);
            }
            Session session = new Session(row, decode(row.payload()));
            try {
                if (!session.payload.directory().equals(openList.offlineDirectoryForPath(session.path())))
                    throw new IllegalStateException("目标账号或根目录映射已变化");
                openList.requireOrganizationQueuesQuiet();
                if (!process.equals(row.ownerToken())) {
                    // Never checkpoint on behalf of a live/unknown owner: even read-only observation cannot fence it.
                    organizer.verifyFiles(session.payload.snapshot());
                    return;
                }
                if (row.phase().equals("FILES")) session.files();
                session.accept();
            } catch (RuntimeException failure) {
                if (process.equals(session.row.ownerToken())) session.waiting(failure);
            }
        } finally { synchronized (active) { active.remove(owner); } }
    }

    private final class Session {
        Row row;
        Payload payload;
        Session(Row row, Payload payload) { this.row = Objects.requireNonNull(row); this.payload = payload; }
        String path() { return payload.snapshot().plan().targetDirectory(); }
        void files() {
            if (!process.equals(row.ownerToken())) throw new IllegalStateException("禁止接管未知进程写权限");
            save("FILES", "RUNNING", null);
            organizer.resume(payload.snapshot(), payload.actions(), (key, state) -> {
                if (state == ActionState.SENT) {
                    openList.requireOrganizationQueuesQuiet();
                    if (!payload.directory().equals(openList.offlineDirectoryForPath(path())))
                        throw new IllegalStateException("目标账号映射已变化");
                }
                payload.actions().put(key, state);
                save("FILES", "RUNNING", null);
            });
            save("REFRESH", "WAITING", null);
        }
        void accept() {
            if (Set.of("COMPLETE", "ISOLATED").contains(row.status())) return;
            if (!process.equals(row.ownerToken())) throw new IllegalStateException("刷新写权限不属于当前进程");
            if (!payload.directory().equals(openList.offlineDirectoryForPath(path())))
                throw new IllegalStateException("目标账号映射已变化");
            organizer.verifyFiles(payload.snapshot());
            if (row.phase().equals("REFRESH")) {
                if (!payload.refreshSent()) {
                    payload = payload.withRefreshSent(true);
                    save("REFRESH", "RUNNING", null);
                    var outcome = acceptance.refresh(row.taskType(), path());
                    if (outcome.status() == AutoSymlinkRefreshService.Status.SKIPPED) {
                        payload = payload.withRefreshSent(false);
                        save("REFRESH", "WAITING", "REFRESH_NOT_SENT");
                        throw new IllegalStateException("刷新尚未发送");
                    }
                }
                // A refresh timeout is not permission to resubmit. VERIFY reconciles visibility only.
                save("VERIFY", "WAITING", null);
            }
            if (payload.refreshSent() && row.phase().equals("VERIFY")) {
                try { acceptance.verify(row.taskType(), payload); }
                catch (OrganizationVerificationException mismatch) {
                    if (!row.taskType().equals("movie") || !mismatch.reason().equals("EMBY_IDENTITY_MISMATCH")) throw mismatch;
                    if (payload.identityRepair() != null)
                        throw new OrganizationVerificationException("EMBY_REPAIR_OUTCOME_UNKNOWN", false);
                    var candidate = acceptance.movieRepairCandidate(payload, mismatch.movie());
                    payload = payload.withIdentityRepair(new IdentityRepair(mismatch.movie(), mismatch.paths(), payload.tmdbId(),
                            candidate.candidateId(), java.time.Instant.now().toString()));
                    save("VERIFY", "RUNNING", "EMBY_REPAIR_SENT");
                    // Once intent commits, even a lost response only permits observation on re-entry.
                    organizer.verifyFiles(payload.snapshot());
                    openList.requireOrganizationQueuesQuiet();
                    if (!payload.directory().equals(openList.offlineDirectoryForPath(path())))
                        throw new OrganizationVerificationException("EMBY_REPAIR_MAPPING_CHANGED", true);
                    try { acceptance.applyMovieRepair(payload, mismatch.movie(), mismatch.paths(), candidate); }
                    catch (OrganizationVerificationException refused) { throw refused; }
                    catch (RuntimeException unknown) { throw new OrganizationVerificationException("EMBY_REPAIR_OUTCOME_UNKNOWN", false); }
                    save("VERIFY", "WAITING", "EMBY_REPAIR_APPLIED_PENDING_VERIFY");
                    acceptance.verify(row.taskType(), payload);
                }
                acceptance.recordCompletion(row.taskType(), row.taskId(), payload);
                save("COMPLETE", "COMPLETE", null);
                scheduler.completeOrganizationRecovery(row.taskType() + ":" + row.taskId(), payload.directory(), payload.hash());
            }
        }
        void waiting(RuntimeException failure) {
            int attempt = row.attempt() + 1;
            var classified = failure instanceof OrganizationVerificationException known ? known : null;
            String status = classified != null && classified.isolated() || attempt >= 12 ? "ISOLATED" : "WAITING";
            // Only controlled codes leave this boundary; no upstream bodies or signed URLs.
            String error = classified == null ? row.lastError() == null ? failure.getClass().getSimpleName() : row.lastError()
                    : classified.reason();
            if (status.equals("ISOLATED") && classified != null)
                acceptance.recordIsolation(row.taskType(), row.taskId(), error);
            persist(row.phase(), status, attempt, error);
        }
        void save(String phase, String status, String error) { persist(phase, status, row.attempt(), error); }
        void persist(String phase, String status, int attempt, String error) {
            long next = System.currentTimeMillis() + Math.min(1800000L, 30000L << Math.min(attempt, 6));
            if (mapper.checkpoint(row, encode(payload), phase, status, next, attempt, error) != 1)
                throw new IllegalStateException("整理日志所有权或版本已变化，禁止后续写入");
            row = new Row(row.taskType(), row.taskId(), encode(payload), phase, status, row.version() + 1,
                    row.ownerToken(), next, attempt, error);
        }
    }

    private String encode(Payload payload) {
        try { return json.writeValueAsString(payload); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("整理日志序列化失败"); }
    }
    private Payload decode(String payload) {
        try { return json.readValue(payload, Payload.class); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("整理日志无效，禁止恢复写入"); }
    }
}
