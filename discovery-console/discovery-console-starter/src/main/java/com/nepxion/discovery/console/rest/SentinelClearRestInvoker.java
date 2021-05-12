package com.nepxion.discovery.console.rest;

/**
 * <p>Title: Nepxion Discovery</p>
 * <p>Description: Nepxion Discovery</p>
 * <p>Copyright: Copyright (c) 2017-2050</p>
 * <p>Company: Nepxion</p>
 * @author Haojun Ren
 * @version 1.0
 */

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.client.RestTemplate;

import com.nepxion.discovery.common.exception.DiscoveryException;
import com.nepxion.discovery.console.constant.ConsoleConstant;
import com.nepxion.discovery.console.resource.ServiceResource;

public class SentinelClearRestInvoker extends AbstractRestInvoker {
    private String type;

    public SentinelClearRestInvoker(ServiceResource serviceResource, String serviceId, RestTemplate restTemplate, String type) {
        super(serviceResource, serviceId, restTemplate);

        this.type = type.toLowerCase().trim();

        if (!ConsoleConstant.SENTINEL_TYPES.contains(type)) {
            throw new DiscoveryException("Invalid sentinel type for '" + type + "', it must be one of " + ConsoleConstant.SENTINEL_TYPES);
        }
    }

    @Override
    protected String getDescription() {
        return "Sentinel rules cleared";
    }

    @Override
    protected String getSuffixPath() {
        String path = StringUtils.equals(type, "param-flow") ? "sentinel-param" : "sentinel-core";

        return path + "/clear-" + type + "-rules";
    }

    @Override
    protected String doRest(String url) {
        return restTemplate.postForEntity(url, null, String.class).getBody();
    }
}