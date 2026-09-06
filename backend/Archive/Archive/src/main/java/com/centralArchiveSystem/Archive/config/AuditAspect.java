package com.centralArchiveSystem.Archive.config;

import com.centralArchiveSystem.Archive.audit.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

@Aspect
@Component
public class AuditAspect {

    private static final Logger logger = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    public AuditAspect(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Pointcut("execution(* com.centralArchiveSystem.Archive..*Controller.*(..)) && " +
              "!execution(* com.centralArchiveSystem.Archive..*ExceptionHandler.*(..))")
    public void controllerMethods() {
    }

    @Around("controllerMethods()")
    public Object auditLog(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        Object result = null;
        Exception exception = null;

        String action = determineAction(joinPoint);
        String entityType = determineEntityType(joinPoint);
        String entityId = determineEntityId(joinPoint, null);

        String oldValues = null;
        if ("UPDATE".equals(action) || "DELETE".equals(action)) {
            oldValues = determineOldValues(entityType, entityId);
        }

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Exception ex) {
            exception = ex;
            throw ex;
        } finally {
            try {
                if (entityId == null) {
                    entityId = determineEntityId(joinPoint, result);
                }
                String user = getCurrentUser();
                String newValues = determineNewValues(joinPoint, result);
                long executionTime = System.currentTimeMillis() - startTime;
                String status = exception == null ? "SUCCESS" : "FAILURE";
                String errorMessage = exception != null ? exception.getMessage() : null;

                auditLogService.log(user, action, entityType, entityId, oldValues, newValues, executionTime, status, errorMessage);
            } catch (Exception e) {
                logger.error("Failed to create audit log", e);
            }
        }
    }

    private String getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return authentication.getName();
        }
        return "anonymous";
    }

    private String determineAction(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName().toLowerCase();

        if (methodName.contains("reject")) {
            return "REJECT";
        } else if (methodName.contains("approve")) {
            return "APPROVE";
        } else if (methodName.contains("archive")) {
            return "ARCHIVE";
        } else if (methodName.contains("import") || methodName.contains("receive") || methodName.contains("verify")) {
            return "IMPORT";
        } else if (methodName.contains("export") || methodName.contains("create") || methodName.contains("save") ||
                   methodName.contains("add") || methodName.contains("register")) {
            return "CREATE";
        } else if (methodName.contains("update") || methodName.contains("edit") || methodName.contains("modify")) {
            return "UPDATE";
        } else if (methodName.contains("delete") || methodName.contains("remove")) {
            return "DELETE";
        } else if (methodName.contains("get") || methodName.contains("find") || methodName.contains("list") ||
                   methodName.contains("read") || methodName.contains("download") || methodName.contains("view")) {
            return "READ";
        }
        return "EXECUTE";
    }

    private String determineEntityType(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();

        for (Parameter parameter : parameters) {
            String paramName = parameter.getName().toLowerCase();
            if (!paramName.equals("request") && !paramName.equals("response") && !paramName.equals("authentication")) {
                String typeName = parameter.getType().getSimpleName();
                if (!typeName.equals("String") && !typeName.equals("Long") && !typeName.equals("Integer")
                        && !typeName.equals("Boolean") && !typeName.equals("Pageable")) {
                    return typeName;
                }
            }
        }

        String className = joinPoint.getSignature().getDeclaringType().getSimpleName();
        if (className.endsWith("Controller")) {
            return className.replace("Controller", "");
        }
        return className;
    }

    private String determineEntityId(ProceedingJoinPoint joinPoint, Object result) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();

        for (int i = 0; i < parameters.length; i++) {
            String paramName = parameters[i].getName().toLowerCase();
            if (paramName.contains("id") && args[i] != null) {
                return args[i].toString();
            }
        }

        if (result != null) {
            try {
                Method getIdMethod = result.getClass().getMethod("getId");
                Object id = getIdMethod.invoke(result);
                if (id != null) {
                    return id.toString();
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    private String determineOldValues(String entityType, String entityId) {
        if (entityType == null || entityId == null) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(entityId);
        } catch (IllegalArgumentException e) {
            return null;
        }

        Class<?> entityClass = findEntityClass(entityType);
        if (entityClass == null) {
            return null;
        }

        try {
            Object entity = entityManager.find(entityClass, id);
            if (entity != null) {
                return objectMapper.writeValueAsString(entity);
            }
        } catch (Exception e) {
            logger.debug("Failed to load old values for entityType={}, id={}", entityType, entityId, e);
        }
        return null;
    }

    private Class<?> findEntityClass(String entityType) {
        for (EntityType<?> entity : entityManager.getMetamodel().getEntities()) {
            String simpleName = entity.getJavaType().getSimpleName();
            if (simpleName.equalsIgnoreCase(entityType)
                    || simpleName.equalsIgnoreCase(entityType + "Entity")
                    || simpleName.replace("Entity", "").equalsIgnoreCase(entityType)) {
                return entity.getJavaType();
            }
        }
        return null;
    }

    private String determineNewValues(ProceedingJoinPoint joinPoint, Object result) {
        if (result == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return result.toString();
        }
    }
}
