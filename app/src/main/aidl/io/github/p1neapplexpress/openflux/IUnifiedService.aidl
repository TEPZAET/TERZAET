// IUnifiedService.aidl
package io.github.p1neapplexpress.openflux;

interface IUnifiedService {
    boolean isVpnRunning();
    boolean isShutdownComplete();
    long    measureDataPathLatency();
    void    stopVpn();

    boolean isFServiceRunning();
    String  nativeError();
    void    stopOpenFluxNative();
    void    startOpenFluxNative(String transport, in String[] args, String encryptionKey);
    void    startHysteriaNative(String uri, in String[] fallbackArgs, String fallbackEncryptionKey, boolean allowFallback);
    void    startTun2Socks();
    int     getFd();
}
