package org.easypos.request.cache;

import org.apache.commons.collections4.map.LRUMap;

import java.util.Map;

public enum RequestLRUCache {
    INSTANCE;

    private LRUMap requestCache = new LRUMap(100);

    public synchronized void put(String key, Map<String, Object> response) {
        requestCache.put(key, response);
    }

    public synchronized boolean contains(String key) {
        return requestCache.containsKey(key);
    }

    public synchronized Map<String, Object> get(String key) {
        return (Map<String, Object>) requestCache.get(key);
    }

    public static String generateRequestId(String prefix, String key) {
        return prefix.concat("-").concat(key);
    }
}
