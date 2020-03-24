/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.rpc.support;

import com.alibaba.fastjson.JSON;
import org.apache.dubbo.common.Constants;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.utils.*;
import org.apache.dubbo.rpc.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final public class MockInvoker<T> implements Invoker<T> {
    private final static ProxyFactory proxyFactory = ExtensionLoader.getExtensionLoader(ProxyFactory.class).getAdaptiveExtension();
    private final static Map<String, Invoker<?>> mocks = new ConcurrentHashMap<String, Invoker<?>>();
    private final static Map<String, Throwable> throwables = new ConcurrentHashMap<String, Throwable>();

    private final URL url;

    public MockInvoker(URL url) {
        this.url = url;
    }

    public static Object parseMockValue(String mock) throws Exception {
        return parseMockValue(mock, null);
    }

    public static Object parseMockValue(String mock, Type[] returnTypes) throws Exception {
        Object value = null;
        if ("empty".equals(mock)) {
            //如果是empty value等于对应类型空实现 具体逻辑见getEmptyObject
            value = ReflectUtils.getEmptyObject(returnTypes != null && returnTypes.length > 0 ? (Class<?>) returnTypes[0] : null);
        } else if ("null".equals(mock)) {
            value = null;
        } else if ("true".equals(mock)) {
            value = true;
        } else if ("false".equals(mock)) {
            value = false;
        } else if (mock.length() >= 2 && (mock.startsWith("\"") && mock.endsWith("\"")
                || mock.startsWith("\'") && mock.endsWith("\'"))) {
            //去除前后引号
            value = mock.subSequence(1, mock.length() - 1);
        } else if (returnTypes != null && returnTypes.length > 0 && returnTypes[0] == String.class) {
            //返回类型为String类型
            value = mock;
        } else if (StringUtils.isNumeric(mock, false)) {
            //如果是数字
            value = JSON.parse(mock);
        } else if (mock.startsWith("{")) {
            //{开头 Json反序列化解析为Map
            value = JSON.parseObject(mock, Map.class);
        } else if (mock.startsWith("[")) {
            //[开头 Json反序列化解析为List
            value = JSON.parseObject(mock, List.class);
        } else {
            //其他 不做处理
            value = mock;
        }
        //如果returnTypes不为空 进一步做处理
        if (ArrayUtils.isNotEmpty(returnTypes)) {
            //returnTypes[0]为目标类型  returnTypes[1]为目标类型的泛型类型
            value = PojoUtils.realize(value, (Class<?>) returnTypes[0], returnTypes.length > 1 ? returnTypes[1] : null);
        }
        return value;
    }

    @Override
    public Result invoke(Invocation invocation) throws RpcException {
        //获取针对方法的mock配置
        String mock = getUrl().getParameter(invocation.getMethodName() + "." + Constants.MOCK_KEY);
        if (invocation instanceof RpcInvocation) {
            ((RpcInvocation) invocation).setInvoker(this);
        }
        //如果针对方法没有mock配置 取接口级别的mock配置
        if (StringUtils.isBlank(mock)) {
            mock = getUrl().getParameter(Constants.MOCK_KEY);
        }

        //如果没有mock配置抛出异常
        if (StringUtils.isBlank(mock)) {
            throw new RpcException(new IllegalAccessException("mock can not be null. url :" + url));
        }
        //标准化mock配置 方便下面匹配
        mock = normalizeMock(URL.decode(mock));
        //return 开头
        if (mock.startsWith(Constants.RETURN_PREFIX)) {
            //去除return
            mock = mock.substring(Constants.RETURN_PREFIX.length()).trim();
            try {
                //获取这个接口的返回类型
                Type[] returnTypes = RpcUtils.getReturnTypes(invocation);
                //mock配置反序列化为对应类型的value
                Object value = parseMockValue(mock, returnTypes);
                return new RpcResult(value);
            } catch (Exception ew) {
                throw new RpcException("mock return invoke error. method :" + invocation.getMethodName()
                        + ", mock:" + mock + ", url: " + url, ew);
            }
        } else if (mock.startsWith(Constants.THROW_PREFIX)) { //throw 开头
            //去除throw
            mock = mock.substring(Constants.THROW_PREFIX.length()).trim();
            //如果mock为空 ，降级为抛出RpcException
            if (StringUtils.isBlank(mock)) {
                throw new RpcException("mocked exception for service degradation.");
            } else { // user customized class
                //反序列化为用户自定义异常
                Throwable t = getThrowable(mock);
                //用RpcException包装自定义异常抛出
                //注意异常类型设置为业务异常
                throw new RpcException(RpcException.BIZ_EXCEPTION, t);
            }
        } else { //impl mock
            try {
                //default或者具体接口实现类，默认使用接口Mock这个类实现mock逻辑
                Invoker<T> invoker = getInvoker(mock);
                return invoker.invoke(invocation);
            } catch (Throwable t) {
                throw new RpcException("Failed to create mock implementation class " + mock, t);
            }
        }
    }

    /**
     * 生成mock异常对象
     * @param throwstr
     * @return
     */
    public static Throwable getThrowable(String throwstr) {
        //缓存逻辑
        Throwable throwable = throwables.get(throwstr);
        if (throwable != null) {
            return throwable;
        }

        try {
            Throwable t;
            //反射获取异常class对象
            Class<?> bizException = ReflectUtils.forName(throwstr);
            Constructor<?> constructor;
            //反射获取异常 参数为string的构造函数
            constructor = ReflectUtils.findConstructor(bizException, String.class);
            //创建异常对象
            t = (Throwable) constructor.newInstance(new Object[]{"mocked exception for service degradation."});
            //缓存大小限制1000
            if (throwables.size() < 1000) {
                throwables.put(throwstr, t);
            }
            return t;
        } catch (Exception e) {
            //如果反射出现异常，降级为直接返回RpcException
            throw new RpcException("mock throw error :" + throwstr + " argument error.", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Invoker<T> getInvoker(String mockService) {
        //缓存
        Invoker<T> invoker = (Invoker<T>) mocks.get(mockService);
        if (invoker != null) {
            return invoker;
        }

        //反射得到接口类class对象
        Class<T> serviceType = (Class<T>) ReflectUtils.forName(url.getServiceInterface());
        //得到接口mock类对象
        T mockObject = (T) getMockObject(mockService, serviceType);
        //生成代理
        invoker = proxyFactory.getInvoker(mockObject, serviceType, url);
        //缓存大小1000
        if (mocks.size() < 10000) {
            mocks.put(mockService, invoker);
        }
        return invoker;
    }

    /**
     * 获取serviceType对应mock实现对象
     * @param mockService
     * @param serviceType
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Object getMockObject(String mockService, Class serviceType) {
        //如果mock配置为default 对应mock实现类为 接口名+Mock
        //否则为指定了具体实现类
        if (ConfigUtils.isDefault(mockService)) {
            mockService = serviceType.getName() + "Mock";
        }

        //反射得到mock实现类class对象
        //注意这边如果找不到 会抛出运行时异常
        Class<?> mockClass = ReflectUtils.forName(mockService);
        //判断是否实现了接口
        if (!serviceType.isAssignableFrom(mockClass)) {
            throw new IllegalStateException("The mock class " + mockClass.getName() +
                    " not implement interface " + serviceType.getName());
        }

        try {
            //创建实例
            return mockClass.newInstance();
        } catch (InstantiationException e) {
            throw new IllegalStateException("No default constructor from mock class " + mockClass.getName(), e);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }


    /**
     * Normalize mock string:
     *
     * <ol>
     * <li>return => return null</li>
     * <li>fail => default</li>
     * <li>force => default</li>
     * <li>fail:throw/return foo => throw/return foo</li>
     * <li>force:throw/return foo => throw/return foo</li>
     * </ol>
     *
     * @param mock mock string
     * @return normalized mock string
     */
    public static String normalizeMock(String mock) {
        if (mock == null) {
            return mock;
        }

        mock = mock.trim();

        if (mock.length() == 0) {
            return mock;
        }

        if (Constants.RETURN_KEY.equalsIgnoreCase(mock)) {
            return Constants.RETURN_PREFIX + "null";
        }

        if (ConfigUtils.isDefault(mock) || "fail".equalsIgnoreCase(mock) || "force".equalsIgnoreCase(mock)) {
            return "default";
        }

        if (mock.startsWith(Constants.FAIL_PREFIX)) {
            mock = mock.substring(Constants.FAIL_PREFIX.length()).trim();
        }

        if (mock.startsWith(Constants.FORCE_PREFIX)) {
            mock = mock.substring(Constants.FORCE_PREFIX.length()).trim();
        }

        if (mock.startsWith(Constants.RETURN_PREFIX) || mock.startsWith(Constants.THROW_PREFIX)) {
            mock = mock.replace('`', '"');
        }

        return mock;
    }

    @Override
    public URL getUrl() {
        return this.url;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void destroy() {
        //do nothing
    }

    @Override
    public Class<T> getInterface() {
        //FIXME
        return null;
    }
}
