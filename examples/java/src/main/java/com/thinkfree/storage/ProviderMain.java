package com.thinkfree.storage;

public final class ProviderMain {
    private ProviderMain() {}

    public static void main(String[] args) throws Exception {
        ProviderConfig config = ProviderConfig.fromEnvironment();
        LocalDirectoryProvider provider = LocalDirectoryProvider.start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "tfo-storage-provider-shutdown"));
        System.out.printf("Thinkfree HTTP Storage Provider listening on %s:%d%n",
                provider.address().getHostString(), provider.address().getPort());
        System.out.printf("Storage root: %s%n", config.storageRoot());
        System.out.printf("Adapter: %s%n", config.adapter());
    }
}
