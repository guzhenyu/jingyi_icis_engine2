package com.jingyicare.jingyi_icis_engine.service.ca.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jingyi.ca.cig")
public class CaClientProperties implements InitializingBean {
    private boolean enabled;
    private String host = "127.0.0.1";
    private int port = 9089;
    private long getSignImageDeadlineMs = 10_000;
    private int maxImageBytes = 5 * 1024 * 1024;

    @Override
    public void afterPropertiesSet() {
        if (!enabled) return;
        if (host == null || host.isBlank() || "0.0.0.0".equals(host.trim()) || "::".equals(host.trim())) {
            throw new IllegalArgumentException("jingyi.ca.cig.host 必须是有效的客户端目标地址");
        }
        if (port <= 0 || port > 65_535) {
            throw new IllegalArgumentException("jingyi.ca.cig.port 必须在 1..65535 范围内");
        }
        if (getSignImageDeadlineMs <= 0 || getSignImageDeadlineMs > 60_000) {
            throw new IllegalArgumentException("jingyi.ca.cig.get-sign-image-deadline-ms 必须在 1..60000 范围内");
        }
        if (maxImageBytes <= 0 || maxImageBytes > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("jingyi.ca.cig.max-image-bytes 必须在 1..10485760 范围内");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public long getGetSignImageDeadlineMs() { return getSignImageDeadlineMs; }
    public void setGetSignImageDeadlineMs(long getSignImageDeadlineMs) { this.getSignImageDeadlineMs = getSignImageDeadlineMs; }
    public int getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(int maxImageBytes) { this.maxImageBytes = maxImageBytes; }
}
