package org.redisson.liveobject.core;

import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.implementation.bind.annotation.SuperCall;
import net.bytebuddy.implementation.bind.annotation.This;
import org.redisson.RedissonAttachedLiveObjectService;
import org.redisson.RedissonClient;
import org.redisson.RedissonReference;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.command.CommandExecutor;
import org.redisson.core.RMap;
import org.redisson.core.RObject;
import org.redisson.liveobject.annotation.REntity;
import org.redisson.liveobject.annotation.RId;
import org.redisson.liveobject.misc.Introspectior;

/**
 *
 * @author ruigu
 */
public class AccessorInterceptor<T, K> {

    private final RedissonClient redisson;
    private final Class originalClass;
    private final String idFieldName;
    private final REntity.NamingScheme namingScheme;
    private final CommandExecutor commandExecutor;
    private RMap liveMap;

    public AccessorInterceptor(RedissonClient redisson, Class entityClass, String idFieldName, CommandExecutor commandExecutor) throws Exception {
        this.redisson = redisson;
        this.originalClass = entityClass;
        this.idFieldName = idFieldName;
        this.commandExecutor = commandExecutor;
        this.namingScheme = ((REntity) entityClass.getAnnotation(REntity.class)).namingScheme().newInstance();
    }

    @RuntimeType
    public Object intercept(@Origin Method method, @SuperCall Callable<?> superMethod, @AllArguments Object[] args, @This T me) throws Exception {
        if (isGetter(method, idFieldName)) {
            return superMethod.call();
        }
        initLiveMapIfRequired(getId(me));
        if (isSetter(method, idFieldName)) {
            superMethod.call();
            try {
                liveMap.rename(getMapKey((K) args[0]));
            } catch (RedisException e) {
                if (e.getMessage() == null || !e.getMessage().startsWith("ERR no such key")) {
                    throw e;
                }
            }
            liveMap = null;
            return null;
        }
        String fieldName = getFieldName(method);
        if (isGetter(method, fieldName)) {
            Object result = liveMap.get(fieldName);
            if (method.getReturnType().isAnnotationPresent(REntity.class)) {
                return redisson.getAttachedLiveObjectService().get((Class<Object>) method.getReturnType(), result);
            } else if (result instanceof RedissonReference) {
                RedissonReference r = ((RedissonReference) result);
                return r.getType().getConstructor(Codec.class, CommandExecutor.class, String.class).newInstance(r.getCodec(), commandExecutor, r.getKeyName());
            }
            return result;
        }
        if (isSetter(method, fieldName)) {
            if (method.getParameterTypes()[0].isAnnotationPresent(REntity.class)) {
                return liveMap.fastPut(fieldName, getREntityId(args[0]));
            } else if (args[0] instanceof RObject) {
                RObject ar = (RObject) args[0];
                return liveMap.fastPut(fieldName, new RedissonReference((Class<RObject>) args[0].getClass(), ar.getName()));
            }
            return liveMap.fastPut(fieldName, args[0]);
        }
        return superMethod.call();
    }

    private void initLiveMapIfRequired(K id) {
        if (liveMap == null) {
            liveMap = redisson.getMap(getMapKey(id));
        }
    }

    private String getFieldName(Method method) {
        return method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4);
    }

    private boolean isGetter(Method method, String fieldName) {
        return method.getName().startsWith("get")
                && method.getName().endsWith(getFieldNameSuffix(fieldName));
    }

    private boolean isSetter(Method method, String fieldName) {
        return method.getName().startsWith("set")
                && method.getName().endsWith(getFieldNameSuffix(fieldName));
    }

    private String getMapKey(K id) {
        return namingScheme.getName(originalClass, idFieldName, id);
    }

    private K getId(T me) throws Exception {
        return (K) originalClass.getDeclaredMethod("get" + getFieldNameSuffix(idFieldName)).invoke(me);
    }

    private static String getFieldNameSuffix(String fieldName) {
        return fieldName.substring(0, 1).toUpperCase() + fieldName.substring(1);
    }
    
    private static Object getFieldValue(Object o, String fieldName) throws Exception {
        return RedissonAttachedLiveObjectService.getActualClass(o.getClass()).getDeclaredMethod("get" + getFieldNameSuffix(fieldName)).invoke(o);
    }

    private static Object getREntityId(Object o) throws Exception {
        String idName = Introspectior
                .getFieldsWithAnnotation(RedissonAttachedLiveObjectService.getActualClass(o.getClass()), RId.class)
                .getOnly()
                .getName();
        return getFieldValue(o, idName);
    }

}
