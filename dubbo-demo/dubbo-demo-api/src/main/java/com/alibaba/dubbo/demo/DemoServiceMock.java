package com.alibaba.dubbo.demo;

import com.alibaba.dubbo.demo.DemoService;

/**
 * @author 10064749
 * @description ${DESCRIPTION}
 * @create 2018-08-27 19:10
 */
public class DemoServiceMock implements DemoService{
    @Override
    public String sayHello(String name) {
        return name+"mock";
    }
}
