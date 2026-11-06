package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.integration.onboarding.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.service.SettingsCipher;
import com.medianexus.orchestrator.service.SettingsService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OnboardingExecutorTest {
    @Test
    void workerPersistsExternalFailureAndRecreatedWorkerReadsBackBeforeContinuing() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules(); Map<String,String> rows = new java.util.concurrent.ConcurrentHashMap<>();
        SystemSettingMapper mapper = mock(SystemSettingMapper.class);
        when(mapper.selectSettingValue(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        doAnswer(c -> {rows.putIfAbsent(c.getArgument(0), c.getArgument(1));return null;}).when(mapper).insertSettingIfAbsent(anyString(),anyString());
        doAnswer(c -> {rows.put(c.getArgument(0), c.getArgument(1));return null;}).when(mapper).upsertSetting(anyString(),anyString());
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        IngestTargetRegistry registry = new IngestTargetRegistry(mapper,json);
        OnboardingRecords records = new OnboardingRecords(mapper,json,registry,tx);
        TargetRegistration registration = new TargetRegistration(mapper,json,registry,tx);
        SettingsCipher cipher = new SettingsCipher(Base64.getEncoder().encodeToString(new byte[32]));
        SettingsService settings = mock(SettingsService.class);
        String now = Instant.now().toString();
        java.nio.file.Path source=java.nio.file.Files.createTempDirectory("onboarding-worker-");
        java.nio.file.Files.createDirectory(source.resolve("Movie"));
        String sourcePrefix=source.toString();
        OnboardingPlan plan = new OnboardingPlan("worker-plan",0,0,"new-target","新账号",List.of("MOVIE"),"/new",null,sourcePrefix,
            List.of(new PlannedBinding("MOVIE","/new/Movie",null,sourcePrefix+"/Movie",sourcePrefix+"/strm","library")),List.of(),
            List.of(new DefaultChange("MOVIE",null,"new-target")),List.of(),true,true,List.of(),now,now);
        var payload=json.createObjectNode();
        payload.putObject("openListConnection").put("timeout","PT0.01S").put("pollInterval","PT0.001S");payload.putObject("cloudDriveConnection");payload.putObject("asConnection");payload.putObject("embyConnection");
        payload.putObject("openListStorage").put("mount_path","/new");
        var runForm=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
                {"task_name":"Movie","media_dir":"unused","symlink_creator":true,"metadata_copyer":true,
                 "num_threads":"4","symlink_dir_checker":false,"symlink_checker":false,"metadata_checker":false,
                 "symlink_size":"0","symlink_ext":".strm","metadata_ext":".nfo"}
                """);
        runForm.put("media_dir",sourcePrefix+"/Movie");
        payload.putObject("asConfigurations").set("MOVIE",runForm);
        records.createPlan(new OnboardingRecords.PlanRecord(plan,cipher.encrypt("onboarding-plan/worker-plan",payload.toString()),Map.of()));
        var ol=mock(OpenListAccountClient.class); var cd=mock(CloudDriveAccountClient.class); var as=mock(AutoSymlinkAccountClient.class); var em=mock(EmbyAccountLibraryClient.class);
        when(ol.createAndRead(any(),eq("new-target"),any())).thenReturn(new OpenListAccountClient.StorageView(71,"/new","PikPak",false,null,null,null));
        when(ol.readStorage(any(),eq(71L))).thenReturn(new OpenListAccountClient.StorageView(71,"/new","PikPak",false,null,null,null));
        OnboardingExecutor worker=new OnboardingExecutor(records,registration,registry,settings,cipher,json,ol,cd,as,em);
        try {
            var start=records.begin(plan.id(),0,0);
            worker.submit(start);
            Execution stopped=awaitStopped(records,plan.id());
            assertThat(stopped.status()).isEqualTo("NEEDS_ACTION");
            assertThat(stopped.steps().get(0).resourceRefs()).contains(new ResourceRef("OpenListStorage","71","/new"));
            assertThat(stopped.issue().message()).contains("PikPak","授权","旧默认未改变");
            assertThat(registry.creationRoot("MOVIE","/legacy")).isEqualTo("/legacy");
            assertThat(json.writeValueAsString(stopped)).doesNotContain("protectedPayload","password");
        } finally {worker.close();}
        when(ol.findCreatedStorage(any(),eq("new-target"),eq("/new"))).thenReturn(Optional.of(new OpenListAccountClient.StorageView(71,"/new","PikPak",true,100L,20L,80L)));
        when(ol.readStorage(any(),eq(71L))).thenReturn(new OpenListAccountClient.StorageView(71,"/new","PikPak",true,100L,20L,80L));
        doThrow(new IllegalStateException("private-token-callback"))
            .when(ol).ensureDirectory(any(),eq("/new"),eq("/new/Movie"));
        OnboardingExecutor recovered=new OnboardingExecutor(records,registration,registry,settings,cipher,json,ol,cd,as,em);
        try {
            recovered.submit(records.execution(plan.id()));
            Execution stopped=awaitStopped(records,plan.id());
            assertThat(stopped.currentStage()).isEqualTo("MEDIA_DIRECTORIES");
            assertThat(stopped.steps().get(0).status()).isEqualTo("SUCCEEDED");
            assertThat(json.writeValueAsString(stopped)).doesNotContain("private-token-callback");
            assertThat(registry.creationRoot("MOVIE","/legacy")).isEqualTo("/legacy");
            verify(ol,times(1)).createAndRead(any(),anyString(),any());
        } finally {recovered.close();}
        doNothing().when(ol).ensureDirectory(any(),eq("/new"),eq("/new/Movie"));
        var connectedResources=new CloudDriveAccountClient.Resources(
                List.of(new CloudDriveAccountClient.CloudView("actual-cloud","/actual-cloud",false)),
                List.of(new CloudDriveAccountClient.MountView(sourcePrefix,"/actual-cloud",true,false)),true,true);
        when(cd.readResources(any())).thenReturn(new CloudDriveAccountClient.Resources(List.of(),List.of(),true,true));
        doAnswer(c -> {when(cd.readResources(any())).thenReturn(connectedResources);return null;})
                .when(cd).connectWebDav(any(),anyString(),any(),any());
        when(as.createAndReadUuid(any(),any())).thenReturn("new-uuid");
        when(as.findCreatedUuid(any(),any())).thenReturn(Optional.of("new-uuid"));
        when(as.readConfiguration(any(),eq("new-uuid"))).thenReturn(runForm);
        when(as.manualRequest(any())).thenReturn(runForm);
        when(ol.readStorage(any(),eq(71L))).thenReturn(new OpenListAccountClient.StorageView(71,"/new","PikPak",true,100L,20L,80L));
        var lib=new EmbyAccountLibraryClient.LibraryView("library","Movies",List.of(sourcePrefix+"/strm"));
        when(em.appendAndVerify(any(),eq("library"),eq(sourcePrefix+"/strm"))).thenReturn(lib);
        when(em.readLibraries(any())).thenReturn(List.of(lib));
        OnboardingExecutor completed=new OnboardingExecutor(records,registration,registry,settings,cipher,json,ol,cd,as,em);
        try {
            completed.submit(records.execution(plan.id()));
            Execution done=awaitStopped(records,plan.id());
            assertThat(done.status()).isEqualTo("SUCCEEDED");
            assertThat(done.defaultsCommitted()).isTrue(); assertThat(done.playbackVerified()).isFalse();
            assertThat(registry.creationRoot("MOVIE","/legacy")).isEqualTo("/new/Movie");
            assertThat(registry.toCloudDrivePath("/new/Movie/Film",null,null)).isEqualTo("/actual-cloud/Movie/Film");
            assertThat(records.plan(plan.id()).protectedPayload()).isNull();
            assertThat(completed.submit(records.execution(plan.id()))).isEqualTo(done);
            verify(ol,times(1)).createAndRead(any(),anyString(),any());
        } finally {completed.close();deleteTestDirectory(source);}
    }
    @Test
    void adultBindingAppendsAndRegistersBothLibraries() throws Exception {
        try (Scenario s = new Scenario()) {
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            Execution done=awaitStopped(s.records,s.plan.id());
            assertThat(done.status()).isEqualTo("SUCCEEDED");
            assertThat(s.registry.readState().targets().get(0).bindings().get(0).embyLibraries()).containsExactly(
                    new IngestTargetState.LibraryBinding("jav","JAV"),new IngestTargetState.LibraryBinding("other","Other"));
            verify(s.em).appendAndVerify(any(),eq("jav"),eq(s.output+"/JAV"));
            verify(s.em).appendAndVerify(any(),eq("other"),eq(s.output+"/Other"));
            assertThat(done.steps().stream().filter(v -> v.stage().equals("EMBY_PATHS")).findFirst().orElseThrow().resourceRefs())
                    .containsExactly(new ResourceRef("EmbyLibrary","jav",s.output+"/JAV"),new ResourceRef("EmbyLibrary","other",s.output+"/Other"));
        }
    }

    @Test
    void primaryAdoptionAllowsStorageBelowMappingOnlyWhenAllRootsBelongToStorage() throws Exception {
        for(String root:List.of("/pikpak/Media/Adult","/pikpak/Media","/pikpak/MediaOther/Adult")){
            try(Scenario s=new Scenario()){
                String now=Instant.now().toString();
                var adoption=new AdoptionPlan("primary-plan",0,0,"primary","主号",true,null,List.of(),List.of(),List.of(),now);
                s.records.createAdoption(new OnboardingRecords.AdoptionRecord(adoption,Map.of()));
                var form=(com.fasterxml.jackson.databind.node.ObjectNode)s.payload.path("asConfigurations").path("ADULT").deepCopy();
                form.put("media_dir","/primary"+root.substring("/pikpak".length()));
                var binding=new IngestTargetState.Binding("ADULT",root,null,"primary-uuid",form.toString(),"/primary-strm/Adult","jav",
                        List.of(new IngestTargetState.LibraryBinding("jav","JAV"),new IngestTargetState.LibraryBinding("other","Other")));
                var target=new IngestTargetState.Target("primary","主号","/pikpak","/primary-cloud","/primary",true,List.of(binding));
                var observation=new OnboardingRecords.Observation(11,"/pikpak/Media","WebDAV","/primary-cloud","/primary",Map.of(),null,List.of(),now,now);
                if(root.equals("/pikpak/MediaOther/Adult")){
                    assertThatThrownBy(() -> s.records.completeAdoption(adoption.id(),0,0,target,observation,s.registration))
                            .isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
                    assertThat(s.registry.readState().managed()).isFalse();
                }else{
                    s.records.completeAdoption(adoption.id(),0,0,target,observation,s.registration);
                    assertThat(s.registry.creationRoot("ADULT",null)).isEqualTo(root);
                }
            }
        }
    }

    @Test
    void lostWebDavResponseRecoversUniqueNewCloudAndRenamesToPlannedRoot() throws Exception {
        try(Scenario s=new Scenario()){
            var root=new java.util.concurrent.atomic.AtomicReference<String>();
            when(s.cd.readResources(any())).thenAnswer(c -> s.resources(root.get()));
            doAnswer(c -> {root.set("/generated");throw new com.medianexus.orchestrator.common.exception.BusinessException(
                    com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY);}).when(s.cd).connectWebDav(any(),anyString(),any(),any());
            doAnswer(c -> {root.set("/planned");return null;}).when(s.cd).renameCreatedCloudRoot(any(),eq("/generated"),eq("/planned"));
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            assertThat(awaitStopped(s.records,s.plan.id()).currentStage()).isEqualTo("CD2_CLOUD");
            s.worker.submit(s.records.execution(s.plan.id()));
            assertThat(awaitStopped(s.records,s.plan.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(s.registry.toCloudDrivePath("/new/Adult/Film",null,null)).isEqualTo("/planned/Adult/Film");
            verify(s.cd,times(1)).connectWebDav(any(),anyString(),any(),any());
            verify(s.cd,times(1)).renameCreatedCloudRoot(any(),eq("/generated"),eq("/planned"));
        }
    }

    @Test
    void lostMountResponseStartsExistingMountWithoutCreatingAgain() throws Exception {
        try(Scenario s=new Scenario()){
            var cloud=new java.util.concurrent.atomic.AtomicBoolean();
            var created=new java.util.concurrent.atomic.AtomicBoolean();
            var mounted=new java.util.concurrent.atomic.AtomicBoolean();
            when(s.cd.readResources(any())).thenAnswer(c -> new CloudDriveAccountClient.Resources(
                    cloud.get()?s.resources("/planned").clouds():List.of(),
                    created.get()?List.of(new CloudDriveAccountClient.MountView(s.source.toString(),"/planned",mounted.get(),false)):List.of(),true,true));
            doAnswer(c -> {cloud.set(true);return null;}).when(s.cd).connectWebDav(any(),anyString(),any(),any());
            doAnswer(c -> {created.set(true);throw new com.medianexus.orchestrator.common.exception.BusinessException(
                    com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY);}).when(s.cd).createMount(any(),any());
            doAnswer(c -> {mounted.set(true);return null;}).when(s.cd).mount(any(),eq(s.source.toString()));
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            assertThat(awaitStopped(s.records,s.plan.id()).currentStage()).isEqualTo("CD2_MOUNT");
            s.worker.submit(s.records.execution(s.plan.id()));
            assertThat(awaitStopped(s.records,s.plan.id()).status()).isEqualTo("SUCCEEDED");
            verify(s.cd,times(1)).createMount(any(),any());
            verify(s.cd,times(1)).mount(any(),eq(s.source.toString()));
        }
    }

    @Test
    void cloudOwnershipRequiresUniqueAccountMarkerBeforeRename() throws Exception {
        for(int matches=0;matches<=2;matches++){
            try(Scenario s=new Scenario()){
                int hits=matches;
                String marker="/MediaNexus-account-adult-target";
                var root=new java.util.concurrent.atomic.AtomicReference<String>();
                when(s.cd.readResources(any())).thenAnswer(c -> {
                    var resources=s.resources(root.get());
                    var clouds=new ArrayList<>(resources.clouds());
                    if(root.get()!=null && hits>0){clouds.add(new CloudDriveAccountClient.CloudView("Other","/other",false));}
                    return new CloudDriveAccountClient.Resources(clouds,resources.mounts(),true,true);
                });
                doAnswer(c -> {root.set("/generated");return null;}).when(s.cd).connectWebDav(any(),anyString(),any(),any());
                doAnswer(c -> {root.set("/planned");return null;}).when(s.cd).renameCreatedCloudRoot(any(),eq("/generated"),eq("/planned"));
                doAnswer(c -> {
                    String path=c.getArgument(1);
                    if(path.endsWith(marker) && (hits==0 || path.equals("/other"+marker) && hits==1)){
                        throw new com.medianexus.orchestrator.common.exception.BusinessException(
                                com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY,"CD2_NOT_FOUND: 目录不存在");
                    }
                    return null;
                }).when(s.cd).verifyDirectory(any(),anyString());
                s.worker.submit(s.records.begin(s.plan.id(),0,0));
                Execution done=awaitStopped(s.records,s.plan.id());
                if(hits==1){
                    assertThat(done.status()).isEqualTo("SUCCEEDED");
                    verify(s.cd).renameCreatedCloudRoot(any(),eq("/generated"),eq("/planned"));
                    verify(s.cd,atLeastOnce()).verifyDirectory(any(),eq("/planned"+marker));
                }else{
                    assertThat(done.status()).isEqualTo("NEEDS_ACTION");
                    assertThat(done.currentStage()).isEqualTo("CD2_CLOUD");
                    assertThat(done.issue().message()).contains("身份目录未唯一命中","旧默认未改变");
                    assertThat(s.registry.readState().managed()).isFalse();
                    verify(s.cd,never()).renameCreatedCloudRoot(any(),anyString(),anyString());
                }
                var order=inOrder(s.ol);
                order.verify(s.ol).ensureDirectory(any(),eq("/new"),eq("/new"+marker));
                order.verify(s.ol).ensureDirectory(any(),eq("/new"),eq("/new/Adult"));
            }
        }
    }

    @Test
    void savedStorageAndAsReferencesSurvivePartialEmbyAppend() throws Exception {
        try(Scenario s=new Scenario()){
            when(s.em.appendAndVerify(any(),eq("other"),eq(s.output+"/Other"))).thenThrow(
                    new com.medianexus.orchestrator.common.exception.BusinessException(com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY))
                    .thenReturn(new EmbyAccountLibraryClient.LibraryView("other","Other",List.of(s.output+"/Other")));
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            Execution stopped=awaitStopped(s.records,s.plan.id());
            assertThat(stopped.currentStage()).isEqualTo("EMBY_PATHS");
            assertThat(s.registry.readState().managed()).isFalse();
            when(s.ol.findCreatedStorage(any(),anyString(),anyString())).thenThrow(new IllegalStateException("must read saved id"));
            when(s.as.findCreatedUuid(any(),any())).thenThrow(new IllegalStateException("must read saved uuid"));
            s.worker.submit(s.records.execution(s.plan.id()));
            Execution done=awaitStopped(s.records,s.plan.id());
            assertThat(done.status()).isEqualTo("SUCCEEDED");
            assertThat(s.registry.readState().targets().get(0).bindings().get(0).embyLibraries()).hasSize(2);
            verify(s.ol,times(1)).createAndRead(any(),anyString(),any());
            verify(s.as,times(1)).createAndReadUuid(any(),any());
            verify(s.cd,times(1)).connectWebDav(any(),anyString(),any(),any());
        }
    }

    @Test
    void lostRenameResponseRecoversSavedRootAndChecksEveryNewCloudMarker() throws Exception {
        try(Scenario s=new Scenario()){
            String marker="/MediaNexus-account-adult-target";
            var root=new java.util.concurrent.atomic.AtomicReference<String>();
            var extra=new java.util.concurrent.atomic.AtomicBoolean();
            when(s.cd.readResources(any())).thenAnswer(c -> {
                var resources=s.resources(root.get());var clouds=new ArrayList<>(resources.clouds());
                if(extra.get()){clouds.add(new CloudDriveAccountClient.CloudView("Unrelated","/unrelated",false));}
                return new CloudDriveAccountClient.Resources(clouds,resources.mounts(),true,true);
            });
            doAnswer(c -> {root.set("/generated");return null;}).when(s.cd).connectWebDav(any(),anyString(),any(),any());
            doAnswer(c -> {root.set("/planned");throw new com.medianexus.orchestrator.common.exception.BusinessException(
                    com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY,"CD2_DEADLINE_EXCEEDED: CD2 协议调用失败");})
                    .when(s.cd).renameCreatedCloudRoot(any(),eq("/generated"),eq("/planned"));
            doThrow(new com.medianexus.orchestrator.common.exception.BusinessException(
                    com.medianexus.orchestrator.common.exception.ErrorCode.BAD_GATEWAY,"CD2_NOT_FOUND: 目录不存在"))
                    .when(s.cd).verifyDirectory(any(),eq("/unrelated"+marker));
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            Execution stopped=awaitStopped(s.records,s.plan.id());
            assertThat(stopped.currentStage()).isEqualTo("CD2_CLOUD");
            assertThat(stopped.steps().stream().filter(v -> v.stage().equals("CD2_CLOUD")).findFirst().orElseThrow().resourceRefs())
                    .containsExactly(new ResourceRef("CloudDriveCloud","WebDAV","/generated"));
            extra.set(true);
            s.worker.submit(s.records.execution(s.plan.id()));
            assertThat(awaitStopped(s.records,s.plan.id()).status()).isEqualTo("SUCCEEDED");
            verify(s.cd,times(1)).connectWebDav(any(),anyString(),any(),any());
            verify(s.cd,times(1)).renameCreatedCloudRoot(any(),anyString(),anyString());
            verify(s.cd).verifyDirectory(any(),eq("/unrelated"+marker));
        }
    }

    @Test
    void newStorageWaitsForInitializationThenContinues() throws Exception {
        try(Scenario s=new Scenario()){
            ((com.fasterxml.jackson.databind.node.ObjectNode)s.payload.path("openListConnection")).put("timeout","PT1S");
            s.savePayload();
            var initializing=new OpenListAccountClient.StorageView(72,"/new","PikPak",false,null,null,null);
            var healthy=new OpenListAccountClient.StorageView(72,"/new","PikPak",true,100L,20L,80L);
            when(s.ol.createAndRead(any(),anyString(),any())).thenReturn(initializing);
            when(s.ol.readStorage(any(),eq(72L))).thenReturn(initializing,healthy);
            s.worker.submit(s.records.begin(s.plan.id(),0,0));
            assertThat(awaitStopped(s.records,s.plan.id()).status()).isEqualTo("SUCCEEDED");
            verify(s.ol,times(1)).createAndRead(any(),anyString(),any());
            verify(s.ol,atLeast(2)).readStorage(any(),eq(72L));
        }
    }

    @Test
    void restoredCloudLicenseContinuesBeforeFirstLoginWithoutDuplicatingCreatedResources() throws Exception {
        try (Scenario s = new Scenario()) {
            when(s.cd.readResources(any())).thenReturn(new CloudDriveAccountClient.Resources(List.of(), List.of(), false, true));
            s.worker.submit(s.records.begin(s.plan.id(), 0, 0));
            var stopped = awaitStopped(s.records, s.plan.id());
            assertThat(stopped.status()).isEqualTo("NEEDS_ACTION");
            assertThat(stopped.currentStage()).isEqualTo("CD2_CLOUD");
            verify(s.cd, never()).connectWebDav(any(), anyString(), any(), any());
            when(s.cd.readResources(any())).thenReturn(s.resources(null));

            s.worker.submit(s.records.execution(s.plan.id()));

            assertThat(awaitStopped(s.records, s.plan.id()).status()).isEqualTo("SUCCEEDED");
            verify(s.cd, times(1)).connectWebDav(any(), anyString(), any(), any());
            verify(s.ol, times(1)).createAndRead(any(), anyString(), any());
            verify(s.as, times(1)).createAndReadUuid(any(), any());
        }
    }

    @Test
    void asBindingPreparesItsExactStrmOutputBeforeCreatingTheTask() throws Exception {
        try (Scenario s = new Scenario()) {
            var preparedAtCreate = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(call -> {
                preparedAtCreate.set(java.nio.file.Files.isDirectory(java.nio.file.Path.of(s.output)));
                return "adult-uuid";
            }).when(s.as).createAndReadUuid(any(), any());
            s.worker.submit(s.records.begin(s.plan.id(), 0, 0));
            assertThat(awaitStopped(s.records, s.plan.id()).status()).isEqualTo("SUCCEEDED");
            assertThat(preparedAtCreate.get()).isTrue();
            verify(s.as).verifyReadableDirectory(any(), eq(s.output));
        }
    }

    private static final class Scenario implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
        final Map<String,String> rows=new java.util.concurrent.ConcurrentHashMap<>();
        final SystemSettingMapper mapper=mock(SystemSettingMapper.class);
        final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
        final IngestTargetRegistry registry;
        final OnboardingRecords records;
        final TargetRegistration registration;
        final SettingsCipher cipher=new SettingsCipher(Base64.getEncoder().encodeToString(new byte[32]));
        final OpenListAccountClient ol=mock(OpenListAccountClient.class);
        final CloudDriveAccountClient cd=mock(CloudDriveAccountClient.class);
        final AutoSymlinkAccountClient as=mock(AutoSymlinkAccountClient.class);
        final EmbyAccountLibraryClient em=mock(EmbyAccountLibraryClient.class);
        final java.nio.file.Path source=java.nio.file.Files.createTempDirectory("onboarding-adult-");
        final String output=source+"/strm/Adult";
        final OnboardingPlan plan;
        final com.fasterxml.jackson.databind.node.ObjectNode payload;
        final OnboardingExecutor worker;
        Scenario() throws Exception {
            when(mapper.selectSettingValue(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
            when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
            doAnswer(c -> {rows.putIfAbsent(c.getArgument(0),c.getArgument(1));return null;}).when(mapper).insertSettingIfAbsent(anyString(),anyString());
            doAnswer(c -> {rows.put(c.getArgument(0),c.getArgument(1));return null;}).when(mapper).upsertSetting(anyString(),anyString());
            when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
            registry=new IngestTargetRegistry(mapper,json); records=new OnboardingRecords(mapper,json,registry,tx);
            registration=new TargetRegistration(mapper,json,registry,tx);
            java.nio.file.Files.createDirectory(source.resolve("Adult"));
            String now=Instant.now().toString();
            plan=new OnboardingPlan("adult-plan",0,0,"adult-target","新账号",List.of("ADULT"),"/new","/planned",source.toString(),
                    List.of(new PlannedBinding("ADULT","/new/Adult",null,source+"/Adult",output,"jav",List.of(
                            new LibraryPath("jav","JAV","JAV",output+"/JAV"),new LibraryPath("other","Other","Other",output+"/Other")))),
                    List.of(),List.of(new DefaultChange("ADULT",null,"adult-target")),List.of(),true,true,List.of(),now,now);
            payload=json.createObjectNode();
            payload.putObject("openListConnection").put("timeout","PT0.01S").put("pollInterval","PT0.001S");payload.putObject("cloudDriveConnection");payload.putObject("asConnection");payload.putObject("embyConnection");
            payload.putObject("openListStorage").put("mount_path","/new"); payload.putObject("mountTemplate");
            payload.set("cloudsBefore",json.createArrayNode());
            var form=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree("""
                    {"task_name":"Adult","symlink_creator":true,"metadata_copyer":true,"num_threads":"4",
                     "symlink_dir_checker":false,"symlink_checker":false,"metadata_checker":false,
                     "symlink_size":"0","symlink_ext":".strm","metadata_ext":".nfo"}
                    """);
            form.put("media_dir",source+"/Adult");payload.putObject("asConfigurations").set("ADULT",form);
            savePayload();
            var storage=new OpenListAccountClient.StorageView(72,"/new","PikPak",true,100L,20L,80L);
            when(ol.createAndRead(any(),anyString(),any())).thenReturn(storage);
            when(ol.findCreatedStorage(any(),anyString(),anyString())).thenReturn(Optional.of(storage));
            when(ol.readStorage(any(),eq(72L))).thenReturn(storage);
            when(cd.readResources(any())).thenReturn(resources(null));
            doAnswer(c -> {when(cd.readResources(any())).thenReturn(resources("/planned"));return null;})
                    .when(cd).connectWebDav(any(),anyString(),any(),any());
            when(as.createAndReadUuid(any(),any())).thenReturn("adult-uuid");
            when(as.findCreatedUuid(any(),any())).thenReturn(Optional.of("adult-uuid"));
            when(as.readConfiguration(any(),anyString())).thenReturn(form);when(as.manualRequest(any())).thenReturn(form);
            var jav=new EmbyAccountLibraryClient.LibraryView("jav","JAV",List.of(output+"/JAV"));
            var other=new EmbyAccountLibraryClient.LibraryView("other","Other",List.of(output+"/Other"));
            when(em.appendAndVerify(any(),eq("jav"),eq(output+"/JAV"))).thenReturn(jav);
            when(em.appendAndVerify(any(),eq("other"),eq(output+"/Other"))).thenReturn(other);
            when(em.readLibraries(any())).thenReturn(List.of(jav,other));
            worker=new OnboardingExecutor(records,registration,registry,mock(SettingsService.class),cipher,json,ol,cd,as,em);
        }
        CloudDriveAccountClient.Resources resources(String path) {
            return new CloudDriveAccountClient.Resources(path==null?List.of():List.of(new CloudDriveAccountClient.CloudView("WebDAV",path,false)),
                    path==null?List.of():List.of(new CloudDriveAccountClient.MountView(source.toString(),"/planned",true,false)),true,true);
        }
        void savePayload() { rows.put("target_plan_"+plan.id(),encode(new OnboardingRecords.PlanRecord(plan,cipher.encrypt("onboarding-plan/"+plan.id(),payload.toString()),Map.of()))); }
        String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new AssertionError(e);}}
        @Override public void close() throws Exception {worker.close();deleteTestDirectory(source);}
    }
    private static void deleteTestDirectory(java.nio.file.Path directory) throws Exception {
        try (var paths = java.nio.file.Files.walk(directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
        }
    }
    private Execution awaitStopped(OnboardingRecords records,String id) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){
            Execution v=records.execution(id).view();
            if(!v.status().equals("RUNNING")&&!v.status().equals("PENDING")){return v;}
            Thread.sleep(5);
        }
        throw new AssertionError("worker did not persist a terminal/needs-action result");
    }
}
