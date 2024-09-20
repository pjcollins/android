.PHONY: leeroy jenkins leeroy-all

jenkins:
ifeq ($(PREPARE_CI_PR)$(PREPARE_CI),00)
	$(MAKE) PREPARE_ARGS=-a prepare
else
	$(MAKE) prepare
endif
ifneq ("$(wildcard $(topdir)/external/androidtools/monodroid/monodroid.proj)","")
	cd $(topdir)/external/androidtools/monodroid && git -c submodule.external/xamarin-android.update=none submodule update --init --recursive
	$(call DOTNET_BINLOG,build-commercial) $(SOLUTION) -t:BuildExternal
endif
	$(MAKE) leeroy

leeroy:
	$(call DOTNET_BINLOG,leeroy) $(SOLUTION) $(_MSBUILD_ARGS)
	$(call DOTNET_BINLOG,setup-workload) -t:ConfigureLocalWorkload build-tools/create-packs/Microsoft.Android.Sdk.proj
