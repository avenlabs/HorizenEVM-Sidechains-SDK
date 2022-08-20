package com.horizen.account.api.rpc.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.horizen.account.api.rpc.handler.RpcException;
import com.horizen.account.api.rpc.request.RpcRequest;
import com.horizen.account.api.rpc.utils.RpcCode;
import com.horizen.account.api.rpc.utils.RpcError;
import com.horizen.evm.utils.Address;
import com.horizen.evm.utils.BigIntDeserializer;
import com.horizen.evm.utils.BigIntSerializer;
import com.horizen.evm.utils.Hash;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.HashMap;

public class RpcService {
    private final HashMap<String, Method> rpcMethods;
    private final ObjectMapper mapper;

    public RpcService() {
        rpcMethods = new HashMap<>();
        var methods = this.getClass().getDeclaredMethods();
        for (var method : methods) {
            var annotation = method.getAnnotation(RpcMethod.class);
            if (annotation == null) continue;
            rpcMethods.put(annotation.value(), method);
        }
        var module = new SimpleModule();
        module.addSerializer(BigInteger.class, new BigIntSerializer());
        module.addSerializer(Address.class, new Address.Serializer());
        module.addSerializer(Hash.class, new Hash.Serializer());
        module.addDeserializer(BigInteger.class, new BigIntDeserializer());
        module.addDeserializer(Address.class, new Address.Deserializer());
        module.addDeserializer(Hash.class, new Hash.Deserializer());
        mapper = new ObjectMapper();
        mapper.registerModule(module);
        // do not serialize null or empty values
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    }

    public boolean hasMethod(String method) {
        return rpcMethods.containsKey(method);
    }

    private Object[] convertParams(Method method, JsonNode params) throws RpcException {
        var optionalAnnotation = method.getAnnotation(RpcOptionalParameters.class);
        var optionalParameters = optionalAnnotation == null ? 0 : optionalAnnotation.value();
        var parameters = method.getParameterTypes();
        if (!params.isArray() ||
                params.size() > parameters.length ||
                params.size() < parameters.length + optionalParameters) {
            throw new RpcException(RpcError.fromCode(RpcCode.InvalidParams));
        }
        try {
            var convertedParams = new Object[parameters.length];
            for (int i = 0; i < parameters.length; i++) {
                convertedParams[i] = mapper.convertValue(params.get(i), parameters[i]);
            }
            return convertedParams;
        } catch (IllegalArgumentException err) {
            LogManager.getLogger().warn("RPC call with invalid params", err);
            throw new RpcException(RpcError.fromCode(RpcCode.InvalidParams, err.getMessage()));
        }
    }

    public Object execute(RpcRequest req) throws Throwable {
        var method = rpcMethods.get(req.getMethod());
        if (method == null) throw new RpcException(RpcError.fromCode(RpcCode.MethodNotFound));
        var params = convertParams(method, req.getParams());
        try {
            return method.invoke(this, params);
        } catch (InvocationTargetException e) {
            // unpack and rethrow potential RpcException
            throw e.getCause();
        }
    }
}
