package com.medianexus.orchestrator.service.settings.targets;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.*;
import com.medianexus.orchestrator.dto.accounts.AccountDtos.*;
import com.medianexus.orchestrator.dto.settings.SettingsDtos.Group;
import com.medianexus.orchestrator.integration.onboarding.*;
import com.medianexus.orchestrator.mapper.SystemSettingMapper;
import com.medianexus.orchestrator.service.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AccountAdoptionPlanTest {
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final Map<String,String> rows = new HashMap<>();
    final SystemSettingMapper mapper = mock(SystemSettingMapper.class);
    final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    final SettingsService settings = mock(SettingsService.class);
    final OpenListAccountClient ol = mock(OpenListAccountClient.class);
    final CloudDriveAccountClient cd = mock(CloudDriveAccountClient.class);
    final AutoSymlinkAccountClient as = mock(AutoSymlinkAccountClient.class);
    final EmbyAccountLibraryClient em = mock(EmbyAccountLibraryClient.class);
    final AutoSymlinkAccountClient forms = new AutoSymlinkAccountClient(json);
    final IngestTargetRegistry registry = new IngestTargetRegistry(mapper,json);
    final SettingsCipher cipher = new SettingsCipher(Base64.getEncoder().encodeToString(new byte[32]));
    final OpenListProperties openlist = new OpenListProperties();
    final CloudDrive2Properties cloud = new CloudDrive2Properties();
    final AutoSymlinkProperties symlink = new AutoSymlinkProperties();
    final DownloadAccountService accounts;

    AccountAdoptionPlanTest() throws Exception {
        when(mapper.selectSettingValue(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        when(mapper.selectSettingValueForUpdate(anyString())).thenAnswer(c -> rows.get(c.getArgument(0)));
        doAnswer(c -> {rows.putIfAbsent(c.getArgument(0),c.getArgument(1));return null;}).when(mapper).insertSettingIfAbsent(anyString(),anyString());
        doAnswer(c -> {rows.put(c.getArgument(0),c.getArgument(1));return null;}).when(mapper).upsertSetting(anyString(),anyString());
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        openlist.setBaseUrl("http://openlist:5244");openlist.setDavUsername("dav-member");openlist.setDavPassword("private-dav-password");
        openlist.setMovieRootPath("/primary/Media/Movie");openlist.setTvRootPath("/primary/Media/TV");openlist.setAdultRootPath("/primary/Media/Adult");
        openlist.setAnimePathTemplate("/primary/Media/Anime/{title}/Season {season}");
        cloud.setIngestPathPrefix("/primary");cloud.setCloudDrivePathPrefix("/Cloud");cloud.setMediaSourcePathPrefix("/mount/main");cloud.setStrmPathPrefix("/strm");
        when(settings.effectiveFingerprint(anyString())).thenAnswer(c -> "effective:"+c.getArgument(0));
        when(settings.get(anyString())).thenAnswer(c -> new Group(c.getArgument(0),"fixture",0,null,"RESTART","EFFECTIVE",List.of(),"effective",List.of(),Map.of(),Map.of(),Map.of(),Map.of()));
        when(settings.effectiveSnapshot("openlist",OpenListProperties.class)).thenReturn(openlist);
        when(settings.effectiveSnapshot("clouddrive2",CloudDrive2Properties.class)).thenReturn(cloud);
        when(settings.effectiveSnapshot("autosymlink",AutoSymlinkProperties.class)).thenReturn(symlink);
        when(settings.effectiveSnapshot("emby",EmbyProperties.class)).thenReturn(new EmbyProperties());
        when(ol.readStorages(any())).thenReturn(List.of(new OpenListAccountClient.StorageView(11,"/primary/Media","PikPak",true,100L,20L,80L)));
        when(ol.readStorageTemplate(any(),eq(11L))).thenReturn(json.createObjectNode());
        when(cd.readResources(any())).thenReturn(new CloudDriveAccountClient.Resources(List.of(new CloudDriveAccountClient.CloudView("Existing DAV","/Cloud",false)),List.of(new CloudDriveAccountClient.MountView("/mount/main","/Cloud",true,false)),true,true));
        when(cd.readMountTemplate(any(),eq("/mount/main"))).thenReturn(com.medianexus.orchestrator.integration.clouddrive.proto.MountOption.newBuilder().setMountPoint("/mount/main").setSourceDir("/Cloud").setLocalMount(true).setAutoMount(true).setPermissions("0755").build());
        when(as.manualRequest(any())).thenAnswer(c->forms.manualRequest(c.getArgument(0)));
        when(as.configurationForNewTarget(any(),any())).thenAnswer(c->forms.configurationForNewTarget(c.getArgument(0),c.getArgument(1)));
        var libraries = new ArrayList<EmbyAccountLibraryClient.LibraryView>();
        for (String category:List.of("MOVIE","TV","ANIME","ADULT")) {
            String folder = switch(category){case "MOVIE"->"Movie";case "TV"->"TV";case "ANIME"->"Anime";default->"Adult";};
            var task = switch(category){case "MOVIE"->symlink.getMovie();case "TV"->symlink.getTv();case "ANIME"->symlink.getAnime();default->symlink.getAdult();};
            task.setUuid("as-"+category);
            ObjectNode form = json.createObjectNode();
            for(String key:List.of("task_name","media_dir","symlink_dir","exclude_folder","sync_time","metadata_copyer_mode","num_threads","observer_time","backup_time","backup_ext","symlink_mode","symlink_size","strm_mode","cloud_type","cloud_url","clouddrive2_path","alist_path","symlink_ext","metadata_ext","sign_file","sign_file_url"))form.put(key,"");
            for(String key:List.of("sync_enabled","restart_sync_enabled","sync_scheduled","symlink_creator","metadata_copyer","metadata_covered","metadata_skipped","symlink_dir_checker","symlink_checker","metadata_checker","observer_enabled","observer_symlink_creator","observer_metadata_copyer","observer_symlink_checker","observer_metadata_checker","backup_scheduled","cloud_checker"))form.put(key,false);
            form.put("task_name",folder).put("media_dir","/mount/main/Media/"+folder).put("symlink_dir","/strm/"+folder).put("clouddrive2_path","/mount/main").put("symlink_mode","strm").put("strm_mode","local").put("cloud_type","cd2").put("num_threads","4").put("observer_time","5").put("symlink_size","0").put("symlink_ext",".mkv").put("metadata_ext",".nfo");
            when(as.readConfiguration(any(),eq("as-"+category))).thenAnswer(c->form.deepCopy());
            if(category.equals("ADULT")){libraries.add(new EmbyAccountLibraryClient.LibraryView("jav","JAV",List.of("/strm/Adult/JAV")));libraries.add(new EmbyAccountLibraryClient.LibraryView("other","Other",List.of("/strm/Adult/Other")));}
            else libraries.add(new EmbyAccountLibraryClient.LibraryView(category,folder,List.of("/strm/"+folder)));
        }
        when(em.readLibraries(any())).thenReturn(libraries);
        var records = new OnboardingRecords(mapper,json,registry,tx);
        accounts = new DownloadAccountService(mock(AuthService.class),settings,cipher,registry,new TargetRegistration(mapper,json,registry,tx),records,json,ol,cd,as,em,mock(OnboardingExecutor.class));
    }

    @Test
    void adoptsExplicitExistingMappingWithAllAdultLibrariesWithoutExternalWritesAndPlansExactNewPaths() throws Exception {
        AdoptionPlan plan = accounts.previewAdoption(new AdoptionRequest(0L,"原主号"));
        assertThat(plan.canConfirm()).as(plan.issues().toString()).isTrue();
        var adult = plan.proposedAccount().bindings().stream().filter(b->b.category().equals("ADULT")).findFirst().orElseThrow();
        assertThat(adult.embyLibraries()).extracting(LibraryPath::relativePath).containsExactly("JAV","Other");
        accounts.confirmAdoption(plan.id(),new AdoptionConfirmRequest(0L,0L));
        assertThat(registry.creationRoot("MOVIE",null)).isEqualTo("/primary/Media/Movie");
        assertThat(accounts.list().accounts()).hasSize(1);
        accounts.confirmAdoption(plan.id(),new AdoptionConfirmRequest(0L,0L));
        assertThat(registry.readState().version()).isEqualTo(1);
        symlink.setNewAccountScanParametersJson("{\"num_threads\":\"8\"}");
        OnboardingPlan next = accounts.previewOnboarding(new OnboardingPlanRequest(1L,"新号",List.of("MOVIE","ADULT"),new PikPakAuthorization("web",null,null,"private-refresh-token",null,null),null,"media-b"));
        assertThat(next.canExecute()).isFalse();
        assertThat(next.issues()).extracting(Issue::code).containsExactly("NATIVE_DAV_REQUIRED");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> accounts.execute(next.id(),new ExecuteRequest(0L,1L)))
                .hasMessageContaining("接入前提尚未满足");
        when(cd.verifyNativeWebDav("https://dav.pikpak.ai/","private-native-user","private-native-password"))
                .thenReturn("https://dav.pikpak.ai/");
        var authorized=accounts.authorizeNativeDav(next.id(),new NativeDavRequest(0L,"https://dav.pikpak.ai/","private-native-user","private-native-password"));
        assertThat(authorized.canExecute()).isTrue();
        assertThat(authorized.planVersion()).isEqualTo(1);
        assertThat(json.writeValueAsString(authorized)).doesNotContain("private-native-user","private-native-password");
        var stored=json.readValue(rows.get("target_plan_"+next.id()),OnboardingRecords.PlanRecord.class);
        var protectedPlan=json.readTree(cipher.decrypt("onboarding-plan/"+next.id(),stored.protectedPayload()));
        assertThat(protectedPlan.path("nativeDav").path("password").asText()).isEqualTo("private-native-password");
        assertThat(protectedPlan.path("asConfigurations").path("MOVIE").path("task_name").asText()).isEqualTo("PikPak · media-b · MOVIE");
        assertThat(protectedPlan.path("asConfigurations").path("MOVIE").path("num_threads").asText()).isEqualTo("8");
        var primaryMovie=registry.readState().targets().get(0).bindings().stream().filter(b->b.category().equals("MOVIE")).findFirst().orElseThrow();
        assertThat(json.readTree(primaryMovie.asRequestBodyJson()).path("num_threads").asText()).isEqualTo("4");
        assertThat(next.cloudDrivePrefix()).isEqualTo("/Cloud-media-b");
        assertThat(next.defaultChanges()).extracting(DefaultChange::category).containsExactly("MOVIE","ADULT");
        assertThat(next.bindings().stream().filter(b->b.category().equals("ADULT")).findFirst().orElseThrow().embyLibraries()).extracting(LibraryPath::libraryId).containsExactly("jav","other");
        assertThat(json.writeValueAsString(next)).doesNotContain("private-refresh-token","private-dav-password");
        assertThat(rows.values()).allMatch(value->!value.contains("private-refresh-token")&&!value.contains("private-dav-password"));
        verify(ol,never()).createAndRead(any(),anyString(),any());verify(cd,never()).connectWebDav(any(),anyString(),anyString(),anyString());verify(em,never()).appendAndVerify(any(),anyString(),anyString());
    }

    @Test
    void changedExistingBindingPreventsConfirmationWithoutRegisteringPartialOwnership() {
        AdoptionPlan plan=accounts.previewAdoption(new AdoptionRequest(0L,"原主号"));
        when(em.readLibraries(any())).thenReturn(List.of());
        assertThatThrownBy(()->accounts.confirmAdoption(plan.id(),new AdoptionConfirmRequest(0L,0L))).isInstanceOf(com.medianexus.orchestrator.common.exception.BusinessException.class);
        assertThat(registry.readState().managed()).isFalse();
    }
}
