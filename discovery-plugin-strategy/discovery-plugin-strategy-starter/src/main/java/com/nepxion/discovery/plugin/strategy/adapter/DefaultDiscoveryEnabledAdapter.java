package com.nepxion.discovery.plugin.strategy.adapter;

/**
 * <p>Title: Nepxion Discovery</p>
 * <p>Description: Nepxion Discovery</p>
 * <p>Copyright: Copyright (c) 2017-2050</p>
 * <p>Company: Nepxion</p>
 * @author Haojun Ren
 * @version 1.0
 */

import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.util.CollectionUtils;

import com.nepxion.discovery.common.constant.DiscoveryConstant;
import com.nepxion.discovery.common.entity.ServiceType;
import com.nepxion.discovery.common.exception.DiscoveryException;
import com.nepxion.discovery.common.util.JsonUtil;
import com.nepxion.discovery.common.util.StringUtil;
import com.nepxion.discovery.plugin.framework.adapter.PluginAdapter;
import com.nepxion.discovery.plugin.framework.context.PluginContextHolder;
import com.nepxion.discovery.plugin.strategy.constant.StrategyConstant;
import com.nepxion.discovery.plugin.strategy.filter.StrategyVersionFilter;
import com.nepxion.discovery.plugin.strategy.matcher.DiscoveryMatcherStrategy;
import com.netflix.loadbalancer.Server;

public class DefaultDiscoveryEnabledAdapter implements DiscoveryEnabledAdapter {
    @Autowired(required = false)
    protected List<DiscoveryEnabledStrategy> discoveryEnabledStrategyList;

    @Autowired
    protected DiscoveryMatcherStrategy discoveryMatcherStrategy;

    @Autowired
    protected StrategyVersionFilter strategyVersionFilter;

    @Autowired
    protected PluginAdapter pluginAdapter;

    @Autowired
    protected PluginContextHolder pluginContextHolder;

    @Autowired
    protected DiscoveryClient discoveryClient;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_CONSUMER_ISOLATION_ENABLED + ":false}")
    protected Boolean consumerIsolationEnabled;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_ENVIRONMENT_ROUTE + ":" + StrategyConstant.SPRING_APPLICATION_STRATEGY_ENVIRONMENT_ROUTE_VALUE + "}")
    protected String environmentRoute;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_ZONE_AFFINITY_ENABLED + ":false}")
    protected Boolean zoneAffinityEnabled;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_ZONE_ROUTE_ENABLED + ":true}")
    protected Boolean zoneRouteEnabled;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_REGION_ROUTE_ENABLED + ":false}")
    protected Boolean regionRouteEnabled;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_REGION_ROUTE + ":}")
    protected String regionRoute;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_VERSION_FAILOVER_ENABLED + ":false}")
    protected Boolean versionFailoverEnabled;

    @Value("${" + StrategyConstant.SPRING_APPLICATION_STRATEGY_VERSION_PREFER_ENABLED + ":false}")
    protected Boolean versionPreferEnabled;

    @Override
    public boolean apply(Server server) {
        boolean enabled = applyGroup(server);
        if (!enabled) {
            return false;
        }

        enabled = applyEnvironment(server);
        if (!enabled) {
            return false;
        }

        enabled = applyZone(server);
        if (!enabled) {
            return false;
        }

        enabled = applyRegion(server);
        if (!enabled) {
            return false;
        }

        enabled = applyAddress(server);
        if (!enabled) {
            return false;
        }

        enabled = applyIdBlacklist(server);
        if (!enabled) {
            return false;
        }

        enabled = applyAddressBlacklist(server);
        if (!enabled) {
            return false;
        }

        enabled = applyVersion(server);
        if (!enabled) {
            return false;
        }

        return applyStrategy(server);
    }

    public boolean applyGroup(Server server) {
        if (!consumerIsolationEnabled) {
            return true;
        }

        String serverServiceType = pluginAdapter.getServerServiceType(server);
        if (StringUtils.equals(serverServiceType, ServiceType.GATEWAY.toString())) {
            return true;
        }

        String serverGroup = pluginAdapter.getServerGroup(server);
        String group = pluginAdapter.getGroup();

        return StringUtils.equals(serverGroup, group);
    }

    public boolean applyEnvironment(Server server) {
        String environmentValue = pluginContextHolder.getContextRouteEnvironment();
        if (StringUtils.isEmpty(environmentValue)) {
            return true;
        }

        String serviceId = pluginAdapter.getServerServiceId(server);
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);

        boolean matched = false;
        for (ServiceInstance instance : instances) {
            String instanceEnvironment = pluginAdapter.getInstanceEnvironment(instance);
            if (StringUtils.equals(environmentValue, instanceEnvironment)) {
                matched = true;

                break;
            }
        }

        String environment = pluginAdapter.getServerEnvironment(server);
        if (matched) {
            // ??????????????????????????????Header??????????????????????????????????????????????????????
            return StringUtils.equals(environment, environmentValue);
        } else {
            // ???????????????????????????Common???????????????Common?????????????????????
            return StringUtils.equals(environment, environmentRoute) || StringUtils.equals(environment, DiscoveryConstant.DEFAULT);
        }
    }

    public boolean applyZone(Server server) {
        if (!zoneAffinityEnabled) {
            return true;
        }

        String zone = pluginAdapter.getZone();
        // ???????????????????????????????????????????????????????????????????????????DiscoveryConstant.DEFAULT???????????????????????????
        if (StringUtils.equals(zone, DiscoveryConstant.DEFAULT)) {
            return true;
        }

        String serviceId = pluginAdapter.getServerServiceId(server);
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);

        boolean matched = false;
        for (ServiceInstance instance : instances) {
            String instanceZone = pluginAdapter.getInstanceZone(instance);
            if (StringUtils.equals(zone, instanceZone)) {
                matched = true;

                break;
            }
        }

        String serverZone = pluginAdapter.getServerZone(server);
        if (matched) {
            // ?????????????????????????????????????????????????????????????????????????????????????????????Metadata???zone???????????????????????????
            return StringUtils.equals(serverZone, zone);
        } else {
            // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            if (zoneRouteEnabled) {
                return true;
            } else {
                return StringUtils.equals(serverZone, zone);
            }
        }
    }

    public boolean applyRegion(Server server) {
        String serviceId = pluginAdapter.getServerServiceId(server);

        String region = pluginAdapter.getServerRegion(server);

        String regions = getRegions(serviceId);
        // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????
        // ?????????????????????
        // ?????????????????????????????????????????????????????????????????????????????????
        // ???????????????A?????? -> B?????? -> C?????????A?????????B???????????????????????????C????????????????????????
        // ????????????????????????????????????Header???n-d-region????????????B????????????????????????????????????A?????????B???????????????????????????????????????B????????????????????????????????????????????????C??????????????????????????????????????????????????????C????????????
        // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
        if (StringUtils.isEmpty(regions)) {
            if (regionRouteEnabled) {
                if (StringUtils.isEmpty(regionRoute)) {
                    throw new DiscoveryException("The Region Route value is missing");
                }

                return StringUtils.equals(region, regionRoute);
            } else {
                return true;
            }
        }

        // ??????????????????????????????????????????????????????
        List<String> regionList = StringUtil.splitToList(regions);
        if (regionList.contains(region)) {
            return true;
        }

        // ???????????????????????????????????????????????????????????????
        for (String regionPattern : regionList) {
            if (discoveryMatcherStrategy.match(regionPattern, region)) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    public String getRegions(String serviceId) {
        String regionValue = pluginContextHolder.getContextRouteRegion();
        if (StringUtils.isEmpty(regionValue)) {
            return null;
        }

        String regions = null;
        try {
            Map<String, String> regionMap = JsonUtil.fromJson(regionValue, Map.class);
            regions = regionMap.get(serviceId);
        } catch (Exception e) {
            regions = regionValue;
        }

        return regions;
    }

    public boolean applyAddress(Server server) {
        String serviceId = pluginAdapter.getServerServiceId(server);

        String addresses = getAddresses(serviceId);
        if (StringUtils.isEmpty(addresses)) {
            return true;
        }

        // ??????????????????????????????????????????????????????
        List<String> addressList = StringUtil.splitToList(addresses);
        if (addressList.contains(server.getHost() + ":" + server.getPort()) || addressList.contains(server.getHost()) || addressList.contains(String.valueOf(server.getPort()))) {
            return true;
        }

        // ???????????????????????????????????????????????????????????????
        for (String addressPattern : addressList) {
            if (discoveryMatcherStrategy.match(addressPattern, server.getHost() + ":" + server.getPort()) || discoveryMatcherStrategy.match(addressPattern, server.getHost()) || discoveryMatcherStrategy.match(addressPattern, String.valueOf(server.getPort()))) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    public String getAddresses(String serviceId) {
        String addressValue = pluginContextHolder.getContextRouteAddress();
        if (StringUtils.isEmpty(addressValue)) {
            return null;
        }

        String addresses = null;
        try {
            Map<String, String> addressMap = JsonUtil.fromJson(addressValue, Map.class);
            addresses = addressMap.get(serviceId);
        } catch (Exception e) {
            addresses = addressValue;
        }

        return addresses;
    }

    public boolean applyIdBlacklist(Server server) {
        String serviceId = pluginAdapter.getServerServiceId(server);

        String ids = getIdBlacklists(serviceId);
        if (StringUtils.isEmpty(ids)) {
            return true;
        }

        String id = pluginAdapter.getServerServiceUUId(server);

        // ??????????????????????????????????????????????????????
        List<String> idList = StringUtil.splitToList(ids);
        if (idList.contains(id)) {
            return false;
        }

        // ???????????????????????????????????????????????????????????????
        for (String idPattern : idList) {
            if (discoveryMatcherStrategy.match(idPattern, id)) {
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    public String getIdBlacklists(String serviceId) {
        String idValue = pluginContextHolder.getContextRouteIdBlacklist();
        if (StringUtils.isEmpty(idValue)) {
            return null;
        }

        String ids = null;
        try {
            Map<String, String> idMap = JsonUtil.fromJson(idValue, Map.class);
            ids = idMap.get(serviceId);
        } catch (Exception e) {
            ids = idValue;
        }

        return ids;
    }

    public boolean applyAddressBlacklist(Server server) {
        String serviceId = pluginAdapter.getServerServiceId(server);

        String addresses = getAddressBlacklists(serviceId);
        if (StringUtils.isEmpty(addresses)) {
            return true;
        }

        // ??????????????????????????????????????????????????????
        List<String> addressList = StringUtil.splitToList(addresses);
        if (addressList.contains(server.getHost() + ":" + server.getPort()) || addressList.contains(server.getHost()) || addressList.contains(String.valueOf(server.getPort()))) {
            return false;
        }

        // ???????????????????????????????????????????????????????????????
        for (String addressPattern : addressList) {
            if (discoveryMatcherStrategy.match(addressPattern, server.getHost() + ":" + server.getPort()) || discoveryMatcherStrategy.match(addressPattern, server.getHost()) || discoveryMatcherStrategy.match(addressPattern, String.valueOf(server.getPort()))) {
                return false;
            }
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    public String getAddressBlacklists(String serviceId) {
        String addressValue = pluginContextHolder.getContextRouteAddressBlacklist();
        if (StringUtils.isEmpty(addressValue)) {
            return null;
        }

        String addresses = null;
        try {
            Map<String, String> addressMap = JsonUtil.fromJson(addressValue, Map.class);
            addresses = addressMap.get(serviceId);
        } catch (Exception e) {
            addresses = addressValue;
        }

        return addresses;
    }

    public boolean applyVersion(Server server) {
        String serviceId = pluginAdapter.getServerServiceId(server);

        String versions = getVersions(serviceId);
        if (StringUtils.isEmpty(versions)) {
            // ???????????????????????????????????????????????????????????????????????????????????????
            if (versionPreferEnabled) {
                return strategyVersionFilter.apply(server);
            } else {
                return true;
            }
        } else {
            // ??????????????????????????????????????????????????????????????????????????????????????????????????????
            if (versionFailoverEnabled) {
                List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);

                boolean matched = false;
                for (ServiceInstance instance : instances) {
                    if (strategyVersionFilter.applyVersion(instance)) {
                        matched = true;

                        break;
                    }
                }

                if (!matched) {
                    return strategyVersionFilter.apply(server);
                }
            }
        }

        String version = pluginAdapter.getServerVersion(server);

        // ??????????????????????????????????????????????????????
        List<String> versionList = StringUtil.splitToList(versions);
        if (versionList.contains(version)) {
            return true;
        }

        // ???????????????????????????????????????????????????????????????
        for (String versionPattern : versionList) {
            if (discoveryMatcherStrategy.match(versionPattern, version)) {
                return true;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    public String getVersions(String serviceId) {
        String versionValue = pluginContextHolder.getContextRouteVersion();
        if (StringUtils.isEmpty(versionValue)) {
            return null;
        }

        String versions = null;
        try {
            Map<String, String> versionMap = JsonUtil.fromJson(versionValue, Map.class);
            versions = versionMap.get(serviceId);
        } catch (Exception e) {
            versions = versionValue;
        }

        return versions;
    }

    public boolean applyStrategy(Server server) {
        if (CollectionUtils.isEmpty(discoveryEnabledStrategyList)) {
            return true;
        }

        for (DiscoveryEnabledStrategy discoveryEnabledStrategy : discoveryEnabledStrategyList) {
            boolean enabled = discoveryEnabledStrategy.apply(server);
            if (!enabled) {
                return false;
            }
        }

        return true;
    }

    public PluginAdapter getPluginAdapter() {
        return pluginAdapter;
    }

    public PluginContextHolder getPluginContextHolder() {
        return pluginContextHolder;
    }
}