package com.medianexus.orchestrator.service.settings.targets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.*;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.integration.clouddrive.proto.MountOption;
import com.medianexus.orchestrator.integration.onboarding.*;
import com.medianexus.orchestrator.service.SettingsCipher;
import com.medianexus.orchestrator.service.SettingsService;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 页面关闭不影响执行。每个外部写入前持久化 intent，恢复未决创建只读回，租约和版本共同 fencing。 */
@Service
public class OnboardingExecutor implements AutoCloseable {
    private final OnboardingRecords records;
    private final TargetRegistration registration;
    private final IngestTargetRegistry registry;
    private final SettingsService settings;
    private final SettingsCipher cipher;
    private final ObjectMapper json;
    private final OpenListAccountClient openList;
    private final CloudDriveAccountClient cd2;
    private final AutoSymlinkAccountClient as;
    private final EmbyAccountLibraryClient emby;
    // ponytail: 两个并行接入；持久 claim 是正确性边界，线程池只限制本站网络并发。
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> daemon(runnable, "account-onboarding"));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "account-onboarding-lease"));

    public OnboardingExecutor(OnboardingRecords records, TargetRegistration registration, IngestTargetRegistry registry,
                              SettingsService settings, SettingsCipher cipher, ObjectMapper json, OpenListAccountClient openList,
                              CloudDriveAccountClient cd2, AutoSymlinkAccountClient as, EmbyAccountLibraryClient emby) {
        this.records=records; this.registration=registration; this.registry=registry; this.settings=settings; this.cipher=cipher;
        this.json=json; this.openList=openList; this.cd2=cd2; this.as=as; this.emby=emby;
    }

    public Execution submit(OnboardingRecords.ExecutionRecord current) {
        if (current.view().status().equals("SUCCEEDED") || current.view().status().equals("RUNNING")) { return current.view(); }
        String owner=UUID.randomUUID().toString();
        OnboardingRecords.ExecutionRecord claimed=records.claim(current.view().id(), current.view().executionVersion(), owner, Instant.now());
        try { workers.execute(() -> new Run(claimed, owner).execute()); }
        catch (RejectedExecutionException exception) {
            Run run = new Run(claimed, owner); run.fail(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));
        }
        return claimed.view();
    }

    private final class Run {
        private OnboardingRecords.ExecutionRecord state;
        private final String owner;
        private OnboardingPlan plan;
        private ObjectNode payload;
        private OpenListProperties ol;
        private CloudDrive2Properties cd;
        private AutoSymlinkProperties symlink;
        private EmbyProperties library;
        private OpenListAccountClient.StorageView storage;
        private CloudDriveAccountClient.CloudView cloud;
        private final Map<String,String> uuids=new LinkedHashMap<>();
        private final Map<String,String> libraryNames=new LinkedHashMap<>();

        Run(OnboardingRecords.ExecutionRecord state, String owner) { this.state=state; this.owner=owner; }

        void execute() {
            ScheduledFuture<?> renewal=heartbeat.scheduleAtFixedRate(() -> {
                try { records.renewLease(state.view().id(),owner,Instant.now()); }
                catch (RuntimeException ignored) { /* 业务 checkpoint 仍核对 owner/version，旧结果不能落库。 */ }
            },30,30,TimeUnit.SECONDS);
            try {
                var record=records.plan(state.view().planId()); plan=record.view();
                verifySettings(record);
                if (record.protectedPayload()==null || Instant.parse(plan.createdAt()).plusSeconds(86400).isBefore(Instant.now())) {
                    throw conflict("临时授权或完整模板已过期，请重新预览");
                }
                payload=(ObjectNode)json.readTree(cipher.decrypt("onboarding-plan/"+plan.id(),record.protectedPayload()));
                ol=json.treeToValue(payload.path("openListConnection"),OpenListProperties.class);
                cd=json.treeToValue(payload.path("cloudDriveConnection"),CloudDrive2Properties.class);
                symlink=json.treeToValue(payload.path("asConnection"),AutoSymlinkProperties.class);
                library=json.treeToValue(payload.path("embyConnection"),EmbyProperties.class);
                if(symlink.isDisabled()){throw conflict("AutoSymlink 已停用，不能执行接入");}
                perform("OPENLIST_STORAGE",null,() -> {
                    storage=openList.createAndRead(ol,plan.targetId(),object("openListStorage"));
                    return awaitHealthyStorage();
                },() -> {
                    var saved=savedReference("OPENLIST_STORAGE",null);
                    storage=saved.isPresent()?openList.readStorage(ol,Long.parseLong(saved.get().resourceId()))
                            :openList.findCreatedStorage(ol,plan.targetId(),plan.ingestPrefix())
                                    .orElseThrow(() -> conflict("先前存储创建结果尚未确定，未重发创建"));
                    return awaitHealthyStorage();
                });
                if(!storage.healthy()){throw conflict("OPENLIST_AUTHORIZATION_REQUIRED");}
                perform("MEDIA_DIRECTORIES",null,this::prepareMediaDirectories,this::prepareMediaDirectories);
                perform("CD2_CLOUD",null,this::connectNewCloud,() -> {
                    return payload.path("cloudLoginPrepared").asBoolean() ? readCloud() : connectNewCloud();
                });
                var resolved=records.resolveCloudPath(state.view().id(),state.view().executionVersion(),owner,cloud.path()); plan=resolved.view();
                perform("CD2_MOUNT",null,() -> {
                    var resources=cd2.readResources(cd);
                    var found=resources.mounts().stream().filter(m -> m.mountPoint().equals(plan.mediaSourcePrefix())).toList();
                    if(found.isEmpty()){
                        if(!resources.canAddMount()){throw conflict("CD2 当前不允许新增系统挂载");}
                        cd2.createMount(cd,mountOption());
                    }
                    else {verifyMount(found);}
                    var mount=cd2.readResources(cd).mounts().stream().filter(m -> m.mountPoint().equals(plan.mediaSourcePrefix())).toList();
                    verifyMount(mount);
                    if(!mount.get(0).mounted()){cd2.mount(cd,plan.mediaSourcePrefix());}
                    return readMount();
                },this::readMount);
                perform("MEDIA_READABLE",null,() -> {
                    verifyMediaVisibility(); return directoryRefs();
                },() -> {verifyMediaVisibility(); return directoryRefs();});
                for(var binding:plan.bindings()){
                    String category=binding.category();
                    perform("AS_BINDINGS",category,() -> {
                        prepareStrmOutput(binding);
                        String uuid=as.createAndReadUuid(symlink,configuration(category)); uuids.put(category,uuid);
                        return List.of(new ResourceRef("AutoSymlinkTask",uuid,binding.strmRootPath()));
                    },() -> readAsBinding(binding));
                }
                for(var binding:plan.bindings()){
                    perform("EMBY_PATHS",binding.category(),() -> {
                        for(var path:binding.embyLibraries()){
                            var result=emby.appendAndVerify(library,path.libraryId(),path.path());
                            libraryNames.put(result.id(),result.name());
                        }
                        return readLibrary(binding);
                    },() -> {
                        // 专用追加操作先读回每条目录；部分完成和响应丢失均不重复追加已存在路径。
                        for(var path:binding.embyLibraries()){
                            emby.appendAndVerify(library,path.libraryId(),path.path());
                        }
                        return readLibrary(binding);
                    });
                }
                perform("READBACK",null,() -> {verifyAll();return directoryRefs();},() -> {verifyAll();return directoryRefs();});
                verifySettings(records.plan(plan.id()));
                String now=Instant.now().toString();
                var bindings=new ArrayList<IngestTargetState.Binding>();
                for(var b:plan.bindings()){
                    ObjectNode form=as.readConfiguration(symlink,uuids.get(b.category()));
                    bindings.add(new IngestTargetState.Binding(b.category(),b.rootPath(),b.animePathTemplate(),uuids.get(b.category()),
                            as.manualRequest(form).toString(),b.strmRootPath(),b.embyLibraryId(),
                            b.embyLibraries().stream().map(p -> new IngestTargetState.LibraryBinding(p.libraryId(),p.relativePath())).toList()));
                }
                var target=new IngestTargetState.Target(plan.targetId(),plan.displayName(),plan.ingestPrefix(),plan.cloudDrivePrefix(),plan.mediaSourcePrefix(),true,bindings);
                var observation=new OnboardingRecords.Observation(storage.id(),storage.mountPath(),cloud.name(),cloud.path(),plan.mediaSourcePrefix(),
                        Map.copyOf(libraryNames),capacity(storage,now),List.of(new Check("READBACK","HEALTHY",now,"配置与目录读回通过；未验证影片播放")),now,now);
                records.commitVerifiedExecution(state.view().id(),state.view().executionVersion(),owner,target,observation,registration,Instant.now());
            } catch (RuntimeException | JsonProcessingException failure) { fail(failure); }
            finally {renewal.cancel(false);}
        }

        private List<ResourceRef> storageRefs(){
            if(!storage.driver().equals("PikPak") || !storage.mountPath().equals(plan.ingestPrefix())){
                throw conflict("OpenList 本次存储身份与计划不一致");
            }
            return List.of(new ResourceRef("OpenListStorage",Long.toString(storage.id()),storage.mountPath()));
        }
        private List<ResourceRef> awaitHealthyStorage(){
            List<ResourceRef> refs=storageRefs();
            if(storage.healthy()){return refs;}
            Step prior=state.view().steps().stream().filter(s -> s.stage().equals("OPENLIST_STORAGE")).findFirst().orElseThrow();
            replaceStep(new Step(prior.stage(),null,"RUNNING",prior.attempt(),refs,prior.evidence(),
                    "已保存存储引用，正在等待 PikPak 驱动初始化",null,prior.startedAt(),null),"RUNNING",null);
            long deadline=System.nanoTime()+ol.getTimeout().toNanos();
            while(!storage.healthy()){
                long remaining=deadline-System.nanoTime();
                if(remaining<=0){break;}
                try{TimeUnit.NANOSECONDS.sleep(Math.min(ol.getPollInterval().toNanos(),remaining));}
                catch(InterruptedException interrupted){
                    Thread.currentThread().interrupt();
                    throw conflict("OpenList 初始化等待已中断，请读回已保存存储后继续");
                }
                if(System.nanoTime()>=deadline){break;}
                storage=openList.readStorage(ol,storage.id());storageRefs();
            }
            return refs;
        }
        private Optional<ResourceRef> savedReference(String stage,String category){
            return state.view().steps().stream().filter(s -> s.stage().equals(stage)&&Objects.equals(s.category(),category))
                    .flatMap(s -> s.resourceRefs().stream()).findFirst();
        }
        private List<ResourceRef> readAsBinding(PlannedBinding binding){
            prepareStrmOutput(binding);
            String uuid=savedReference("AS_BINDINGS",binding.category()).map(ResourceRef::resourceId)
                    .orElseGet(() -> as.findCreatedUuid(symlink,configuration(binding.category()))
                            .orElseThrow(() -> conflict("先前 AS 创建结果尚未确定，未重发创建")));
            verifyAsConfiguration(binding.category(),uuid);
            uuids.put(binding.category(),uuid);
            return List.of(new ResourceRef("AutoSymlinkTask",uuid,binding.strmRootPath()));
        }
        private void prepareStrmOutput(PlannedBinding binding){
            try{java.nio.file.Files.createDirectories(java.nio.file.Path.of(binding.strmRootPath()));}
            catch(java.io.IOException exception){throw conflict("计划 STRM 输出目录无法准备；未改写已有文件");}
            as.verifyReadableDirectory(symlink,binding.strmRootPath());
        }
        private void verifyAsConfiguration(String category,String uuid){
            ObjectNode actual=as.readConfiguration(symlink,uuid);
            var fields=configuration(category).fields();
            while(fields.hasNext()){
                var field=fields.next();
                if(!field.getKey().equals("isNew") && !field.getValue().equals(actual.get(field.getKey()))){
                    throw conflict("AS 已保存任务完整配置读回不一致");
                }
            }
        }
        private List<ResourceRef> directoryRefs(){
            var refs=new ArrayList<ResourceRef>();
            refs.add(new ResourceRef("MediaDirectory",null,plan.ingestPrefix()+"/MediaNexus-account-"+plan.targetId()));
            plan.bindings().forEach(b -> refs.add(new ResourceRef("MediaDirectory",null,b.rootPath())));
            return List.copyOf(refs);
        }
        private List<ResourceRef> prepareMediaDirectories(){
            // 持久空目录是本账号的归属证据；先于媒体目录准备，不作为临时或隐藏目录。
            for(var ref:directoryRefs()){openList.ensureDirectory(ol,plan.ingestPrefix(),ref.path());}
            return directoryRefs();
        }
        private ObjectNode object(String key){if(!payload.path(key).isObject()){throw conflict("受保护计划缺少必要模板");}return (ObjectNode)payload.path(key);}
        private ObjectNode configuration(String category){JsonNode v=payload.path("asConfigurations").path(category);if(!v.isObject()){throw conflict("计划缺少完整 AS 模板");}return (ObjectNode)v;}

        /** 未提交登录的前置失败可重新检查；持久标记存在时，恢复只能读回未知结果。 */
        private List<ResourceRef> connectNewCloud(){
            var before=cd2.readResources(cd);
            if(!before.canAddCloud()){throw conflict("CD2 当前不允许新增云盘");}
            if(before.clouds().stream().anyMatch(v -> Objects.equals(plan.cloudDrivePrefix(),v.path()))){
                throw conflict("CD2 计划云盘根已存在，不能按名称复用");
            }
            payload.set("cloudsBefore",json.valueToTree(before.clouds()));
            payload.set("mountsBefore",json.valueToTree(before.mounts()));
            payload.put("cloudLoginPrepared",true);
            records.saveExecutionPayload(state.view().id(),state.view().executionVersion(),owner,
                    cipher.encrypt("onboarding-plan/"+plan.id(),payload.toString()));
            cd2.connectWebDav(cd,payload.path("davUrl").asText(),ol.getDavUsername(),ol.getDavPassword());
            return readCloud();
        }

        private List<ResourceRef> readCloud(){
            if(!payload.path("cloudLoginPrepared").asBoolean() || !payload.path("cloudsBefore").isArray()){
                throw conflict("CD2 缺少登录前资源基线，未重复登录");
            }
            Set<String> before=new HashSet<>(); payload.path("cloudsBefore").forEach(v -> before.add(v.path("path").asText()));
            var resources=cd2.readResources(cd);
            var saved=state.view().steps().stream().filter(s -> s.stage().equals("CD2_CLOUD"))
                    .flatMap(s -> s.resourceRefs().stream()).filter(r -> r.resourceType().equals("CloudDriveCloud")).toList();
            var candidates=resources.clouds().stream().filter(v -> v.path()!=null && !before.contains(v.path())).toList();
            var owned=new ArrayList<CloudDriveAccountClient.CloudView>();
            String marker="/MediaNexus-account-"+plan.targetId();
            for(var candidate:candidates){
                try{cd2.verifyDirectory(cd,candidate.path()+marker);owned.add(candidate);}
                catch(BusinessException failure){
                    // 只有明确不存在能排除候选；认证、权限、超时均不能证明它不属于本账号。
                    if(!failure.getMessage().startsWith("CD2_NOT_FOUND:")){throw failure;}
                }
            }
            candidates=List.copyOf(owned);
            if(candidates.size()!=1){throw conflict("CD2 新云盘账号身份目录未唯一命中；未重复登录");}
            if(saved.size()==1){
                var exact=candidates.stream().filter(v -> v.path().equals(saved.get(0).path())).toList();
                // 重命名响应丢失时原引用会消失；仍须 before 差集唯一指向计划根，不能仅按名称采用。
                if(!exact.isEmpty()){candidates=exact;}
                else if(candidates.size()!=1 || !Objects.equals(plan.cloudDrivePrefix(),candidates.get(0).path())){
                    throw conflict("CD2 已保存云盘引用无法读回；未重复登录");
                }
            }
            if(candidates.size()!=1 || candidates.get(0).locked()){throw conflict("CD2 新云盘无法唯一读回；未重复登录");}
            cloud=candidates.get(0);
            String path=IngestTargetRegistry.normalizePath(cloud.path());
            for(var target:registry.readState().targets()){
                if(overlaps(path,target.cloudDrivePrefix())
                        || (plan.cloudDrivePrefix()!=null && overlaps(plan.cloudDrivePrefix(),target.cloudDrivePrefix()))){
                    throw conflict("CD2 云盘路径与既有目标重叠");
                }
            }
            if(plan.cloudDrivePrefix()!=null&&!plan.cloudDrivePrefix().equals(path)){
                for(var mount:payload.path("mountsBefore")){
                    if(overlaps(path,mount.path("sourceDir").asText())){throw conflict("CD2 新云盘根已被既有挂载引用，未重命名");}
                }
                Step prior=state.view().steps().stream().filter(s -> s.stage().equals("CD2_CLOUD")).findFirst().orElseThrow();
                replaceStep(new Step(prior.stage(),prior.category(),"RUNNING",prior.attempt(),
                        List.of(new ResourceRef("CloudDriveCloud",cloud.name(),path)),prior.evidence(),
                        "已保存本次新云盘引用，正在核对计划根重命名",null,prior.startedAt(),null),"RUNNING",null);
                cd2.renameCreatedCloudRoot(cd,path,plan.cloudDrivePrefix());
                var renamed=cd2.readResources(cd).clouds().stream().filter(v -> plan.cloudDrivePrefix().equals(v.path()) && !v.locked()).toList();
                if(renamed.size()!=1){throw conflict("CD2 计划云盘根重命名尚未读回");}
                cloud=renamed.get(0);path=cloud.path();
            }
            cd2.verifyDirectory(cd,path+marker);
            for(var binding:plan.bindings()){cd2.verifyDirectory(cd,path+binding.rootPath().substring(plan.ingestPrefix().length()));}
            return List.of(new ResourceRef("CloudDriveCloud",cloud.name(),path));
        }

        private MountOption mountOption(){
            JsonNode t=payload.path("mountTemplate");if(!t.isObject()){throw conflict("计划缺少实际系统挂载模板");}
            return MountOption.newBuilder().setMountPoint(plan.mediaSourcePrefix()).setSourceDir(cloud.path())
                    .setLocalMount(t.path("localMount").asBoolean()).setReadOnly(t.path("readOnly").asBoolean())
                    .setAutoMount(t.path("autoMount").asBoolean()).setUid(t.path("uid").asInt()).setGid(t.path("gid").asInt())
                    .setPermissions(t.path("permissions").asText()).setName("medianexus-"+plan.targetId()).build();
        }
        private void verifyMount(List<CloudDriveAccountClient.MountView> matches){
            if(matches.size()!=1 || !matches.get(0).sourceDir().equals(cloud.path()) || matches.get(0).readOnly()){
                throw conflict("CD2 系统挂载身份不符或只读，未覆盖");
            }
        }
        private List<ResourceRef> readMount(){
            var matches=cd2.readResources(cd).mounts().stream().filter(m -> m.mountPoint().equals(plan.mediaSourcePrefix())).toList();
            verifyMount(matches);
            if(!matches.get(0).mounted()){
                cd2.mount(cd,plan.mediaSourcePrefix());
                matches=cd2.readResources(cd).mounts().stream().filter(m -> m.mountPoint().equals(plan.mediaSourcePrefix())).toList();
                verifyMount(matches);
                if(!matches.get(0).mounted()){throw conflict("CD2 系统挂载尚未启动，需核对后继续");}
            }
            return List.of(new ResourceRef("CloudDriveMount",plan.mediaSourcePrefix(),cloud.path()));
        }
        private void verifyMediaVisibility(){
            for(var b:plan.bindings()){
                cd2.verifyDirectory(cd,cloud.path()+b.rootPath().substring(plan.ingestPrefix().length()));
                try(var ignored=java.nio.file.Files.newDirectoryStream(java.nio.file.Path.of(b.mediaSourceRoot()))){}
                catch(java.io.IOException exception){throw conflict("后端容器尚无法读取新系统挂载");}
                as.verifyReadableDirectory(symlink,b.mediaSourceRoot());
            }
        }
        private List<ResourceRef> readLibrary(PlannedBinding binding){
            var libraries=emby.readLibraries(library);
            var refs=new ArrayList<ResourceRef>();
            for(var path:binding.embyLibraries()){
                var matches=libraries.stream().filter(v -> v.id().equals(path.libraryId())).toList();
                if(matches.size()!=1 || matches.get(0).locations().stream().filter(path.path()::equals).count()!=1){
                    throw conflict("Emby 目录追加结果未确定，未重复追加");
                }
                emby.verifyReadableDirectory(library,path.path());
                libraryNames.put(matches.get(0).id(),matches.get(0).name());
                refs.add(new ResourceRef("EmbyLibrary",path.libraryId(),path.path()));
            }
            return List.copyOf(refs);
        }

        private void verifyAll(){
            storage=openList.readStorage(ol,storage.id());storageRefs();
            if(!storage.healthy()){throw conflict("OpenList 存储当前未就绪");}
            if(cd2.readResources(cd).clouds().stream().filter(c -> cloud.path().equals(c.path())&&!c.locked()).count()!=1){
                throw conflict("CD2 最终云盘引用无法唯一读回");
            }
            cd2.verifyDirectory(cd,cloud.path()+"/MediaNexus-account-"+plan.targetId());
            openList.verifyDirectory(ol,plan.ingestPrefix()); readMount(); verifyMediaVisibility();
            for(var b:plan.bindings()){
                verifyAsConfiguration(b.category(),uuids.get(b.category()));
                readLibrary(b);
            }
        }
        private void verifySettings(OnboardingRecords.PlanRecord record){record.fingerprints().forEach(settings::requireEffectiveFingerprint);}

        private void perform(String stage,String category,Supplier<List<ResourceRef>> create,Supplier<List<ResourceRef>> recover){
            verifySettings(records.plan(plan.id()));
            Step previous=state.view().steps().stream().filter(s -> s.stage().equals(stage)&&Objects.equals(s.category(),category)).findFirst().orElse(null);
            String now=Instant.now().toString();
            replaceStep(new Step(stage,category,"RUNNING",previous==null?1:previous.attempt()+1,
                    previous==null?List.of():previous.resourceRefs(),List.of(),"已保存操作意图，正在读回或执行",null,now,null),"RUNNING",null);
            List<ResourceRef> refs=previous==null?create.get():recover.get();
            if(stage.equals("OPENLIST_STORAGE")&&!storage.healthy()){
                replaceStep(new Step(stage,category,"NEEDS_ACTION",previous==null?1:previous.attempt()+1,refs,List.of(),
                        "OpenList 存储已建立，驱动需要授权或验证；原始错误已隐藏",null,now,Instant.now().toString()),"RUNNING",null);
                throw conflict("OPENLIST_AUTHORIZATION_REQUIRED");
            }
            replaceStep(new Step(stage,category,"SUCCEEDED",previous==null?1:previous.attempt()+1,refs,List.of(),"资源读回已确认",null,now,Instant.now().toString()),"RUNNING",null);
        }
        private void replaceStep(Step step,String status,Issue issue){
            Execution v=state.view();var steps=new ArrayList<>(v.steps());
            steps.removeIf(s -> s.stage().equals(step.stage())&&Objects.equals(s.category(),step.category()));steps.add(step);
            Execution next=new Execution(v.id(),v.executionVersion()+1,v.planId(),v.planVersion(),v.targetId(),v.displayName(),v.categories(),status,
                    step.stage(),List.copyOf(steps),v.defaultChanges(),false,false,false,issue,!status.equals("RUNNING"),v.createdAt(),Instant.now().toString(),null);
            state=records.checkpoint(v.id(),v.executionVersion(),owner,next,Instant.now());
        }
        void fail(Exception failure){
            try {
                String code=failure instanceof BusinessException b? switch(b.getCode()){case 401 -> "UPSTREAM_UNAUTHORIZED";case 403 -> "UPSTREAM_FORBIDDEN";case 409 -> "ONBOARDING_CONFLICT";default -> "UPSTREAM_NEEDS_ACTION";}:"ONBOARDING_FAILED";
                Execution v=state.view();String stage=v.currentStage();
                String message="接入未完成，请核对当前阶段的资源、授权及版本";
                // 上述边界的业务异常只含本地定义消息；其他异常不得带入原始 message 或 cause。
                if(failure instanceof BusinessException b && Set.of(400,401,403,404,409,502,503).contains(b.getCode())){
                    message=b.getMessage();
                    if("OPENLIST_AUTHORIZATION_REQUIRED".equals(message)){
                        code="OPENLIST_AUTHORIZATION_REQUIRED";
                        message="PikPak 初始化等待已到期且存储仍未就绪，请检查授权、验证或驱动状态；已保留本次 OpenList 存储，可处理后继续";
                    }
                }
                Issue issue=new Issue(code,stage,null,message+"；旧默认未改变");
                if(stage==null){stage="OPENLIST_STORAGE";}
                String failedStage=stage;
                Step prior=v.steps().stream().filter(s -> s.stage().equals(failedStage)&&s.status().equals("RUNNING")).findFirst()
                        .orElseGet(() -> v.steps().stream().filter(s -> s.stage().equals(failedStage)).reduce((a,b)->b).orElse(new Step(failedStage,null,"PENDING",1,List.of(),List.of(),null,null,Instant.now().toString(),null)));
                replaceStep(new Step(stage,prior.category(),"NEEDS_ACTION",prior.attempt(),prior.resourceRefs(),prior.evidence(),issue.message(),issue,
                        prior.startedAt(),Instant.now().toString()),"NEEDS_ACTION",issue);
            } catch(RuntimeException ignored){/* 旧 owner 或数据库不可用：不能覆盖新执行，租约到期后会显示 INTERRUPTED。 */}
        }
    }

    static Capacity capacity(OpenListAccountClient.StorageView s,String now){
        Long total=s.totalBytes(),used=s.usedBytes(),free=s.freeBytes();
        boolean valid=total!=null&&used!=null&&free!=null&&total>=0&&used>=0&&free>=0&&total<=9007199254740991L&&used<=total&&free==total-used;
        return new Capacity(valid?total:null,valid?used:null,valid?free:null,valid?now:null,valid?"AVAILABLE":"UNAVAILABLE",valid?null:"未取得有效容量");
    }
    private static boolean overlaps(String a,String b){return a.equals(b)||a.startsWith(b+"/")||b.startsWith(a+"/");}
    private static Thread daemon(Runnable runnable,String name){Thread thread=new Thread(runnable,name);thread.setDaemon(true);return thread;}
    private static BusinessException conflict(String message){return new BusinessException(ErrorCode.CONFLICT,message,HttpStatus.CONFLICT);}
    @PreDestroy @Override public void close(){workers.shutdownNow();heartbeat.shutdownNow();}
}
