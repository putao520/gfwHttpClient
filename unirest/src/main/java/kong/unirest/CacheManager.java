/**
 * The MIT License
 *
 * Copyright for portions of unirest-java are held by Kong Inc (c) 2013.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package kong.unirest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;


class CacheManager {

    private final CacheWrapper wrapper = new CacheWrapper();
    private final AsyncWrapper asyncWrapper = new AsyncWrapper();
    private final Cache backingCache;
    private final Cache.KeyGenerator keyGen;

    private Client originalClient;
    private AsyncClient originalAsync;

    public CacheManager() {
        this(100, 0, HashKey::new);
    }

    public CacheManager(int depth, long ttl, Cache.KeyGenerator keyGenerator) {
        this(new CacheMap(depth, ttl), keyGenerator);
    }

    public CacheManager(Cache backing, Cache.KeyGenerator keyGenerator) {
        backingCache = backing;
        if(keyGenerator != null){
            this.keyGen = keyGenerator;
        }else{
            this.keyGen = HashKey::new;
        }
    }

    Client wrap(Client client) {
        this.originalClient = client;
        return wrapper;
    }

    AsyncClient wrapAsync(AsyncClient client) {
        this.originalAsync = client;
        return asyncWrapper;
    }

    private <T> Cache.Key getHash(HttpRequest request, Boolean isAsync, Class<?> responseType) {
        return keyGen.apply(request, isAsync, responseType);
    }

    private static class HashKey implements Cache.Key {
        private final int hash;
        private final Instant time;

        HashKey(HttpRequest request, Boolean isAsync, Class<?> responseType) {
            this(Objects.hash(request.hashCode(), isAsync, responseType),
                 request.getCreationTime());
        }

        HashKey(int hash, Instant time) {
            this.hash = hash;
            this.time = time;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            HashKey key = (HashKey) o;
            return hash == key.hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public Instant getTime() {
            return time;
        }
    }

    private class CacheWrapper implements Client {

        @Override
        public Object getClient() {
            return originalClient.getClient();
        }

        @Override
        public <T> HttpResponse<T> request(HttpRequest request, Function<RawResponse, HttpResponse<T>> transformer) {
            return request(request, transformer, Object.class);
        }

        @Override
        public <T> HttpResponse<T> request(HttpRequest request,
                                           Function<RawResponse, HttpResponse<T>> transformer,
                                           Class<?> responseType) {

            Cache.Key hash = getHash(request, false, responseType);
            return backingCache.get(hash,
                    () -> originalClient.request(request, transformer, responseType));
        }

        @Override
        public Stream<Exception> close() {
            return originalClient.close();
        }

        @Override
        public void registerShutdownHook() {
            originalClient.registerShutdownHook();
        }
    }

    private class AsyncWrapper implements AsyncClient {
        @Override
        public <T> T getClient() {
            return originalAsync.getClient();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> request(HttpRequest request,
                                                              Function<RawResponse, HttpResponse<T>> transformer,
                                                              CompletableFuture<HttpResponse<T>> callback) {
            return request(request, transformer, callback, Object.class);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> request(HttpRequest request,
                                                              Function<RawResponse, HttpResponse<T>> transformer,
                                                              CompletableFuture<HttpResponse<T>> callback,
                                                              Class<?> responseType) {
            Cache.Key key = getHash(request, true, responseType);
            return backingCache.getAsync(key,
                    () -> originalAsync.request(request, transformer, callback, responseType));
        }

        @Override
        public void registerShutdownHook() {
            originalAsync.registerShutdownHook();
        }

        @Override
        public Stream<Exception> close() {
            return originalAsync.close();
        }

        @Override
        public boolean isRunning() {
            return originalAsync.isRunning();
        }
    }

    private static class CacheMap implements Cache {
        private final Map<Cache.Key, Object> map;
        private final int maxSize;
        private final long ttl;

        CacheMap(int maxSize, long ttl) {
            this.maxSize = maxSize;
            this.ttl = ttl;
            this.map = Collections.synchronizedMap(new LinkedHashMap<Cache.Key, Object>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Object> eldest) {
                    return size() > maxSize;
                }
            });
        }

        @Override
        public <T> HttpResponse<T> get(Key key, Supplier<HttpResponse<T>> fetcher) {
            clearOld();
            return (HttpResponse<T>) map.computeIfAbsent(key, (k) -> fetcher.get());
        }

        @Override
        public <T> CompletableFuture getAsync(Key key, Supplier<CompletableFuture<HttpResponse<T>>> fetcher) {
            clearOld();
            return (CompletableFuture) map.computeIfAbsent(key, (k) -> fetcher.get());
        }

        private void clearOld() {
            if (ttl > 0) {
                Instant now = Util.now();
                map.keySet().removeIf(k -> ChronoUnit.MILLIS.between(k.getTime(), now) > ttl);
            }
        }

    }

}
