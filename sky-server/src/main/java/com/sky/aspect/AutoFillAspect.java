package com.sky.aspect;

import com.sky.annotation.AutoFill;
import com.sky.context.BaseContext;
import com.sky.enumeration.OperationType;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

@Aspect
@Slf4j
@Component
public class AutoFillAspect {

    @Pointcut("execution(* com.sky.mapper.*.*(..)) && @annotation(com.sky.annotation.AutoFill)")
    public void autoFillPointCut() {
    }

    @Before("autoFillPointCut()")
    public void autoFill(JoinPoint joinPoint) {
        // 获取到当前被拦截的方法上的注解
        Method method = ((org.aspectj.lang.reflect.MethodSignature) joinPoint.getSignature()).getMethod();
        AutoFill autoFill = method.getAnnotation(AutoFill.class);
        // 获取到注解中的操作类型
        OperationType operationType = autoFill.value();
        // 获取被拦截方法中的参数
        Object[] args = joinPoint.getArgs();
        if (args == null || args.length == 0) {
            return;
        }
        Object entity = args[0];
        // 准备赋值的数据
        LocalDateTime now = LocalDateTime.now();
        Long userId = BaseContext.getCurrentId();
        // 判断操作类型，为对应的属性通过反射来赋值
        if (operationType == OperationType.INSERT) {
            try {
                entity.getClass().getDeclaredMethod("setCreateTime", LocalDateTime.class).invoke(entity, now);
                entity.getClass().getDeclaredMethod("setCreateUser", Long.class).invoke(entity, userId);
                entity.getClass().getDeclaredMethod("setUpdateTime", LocalDateTime.class).invoke(entity, now);
                entity.getClass().getDeclaredMethod("setUpdateUser", Long.class).invoke(entity, userId);

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            try {
                entity.getClass().getDeclaredMethod("setUpdateTime", LocalDateTime.class).invoke(entity, now);
                entity.getClass().getDeclaredMethod("setUpdateUser", Long.class).invoke(entity, userId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
