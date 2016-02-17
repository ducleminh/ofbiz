package org.easypos.user.permission;

import com.google.common.collect.Lists;
import org.easypos.party.EasyPosPartyWorker;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.model.DynamicViewEntity;
import org.ofbiz.entity.model.ModelKeyMap;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

import java.util.*;

public class EasyPosPermissionWorker {

    public static final String EASYPOS_APP_PERMISSION_PREFIX = "EASYPOS_APP";

    public static Map<String, Object> easyPOSGetUserAppPermission(DispatchContext dctx,
                                                                Map<String, ? extends Object> context)
            throws GenericServiceException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String userLoginId = (String) userLoginGenericValue.get("userLoginId");
        String userPartyId = (String) userLoginGenericValue.get("partyId");

        String userAndSecurityAlias = "UserAndSecurity";
        String securityPermissionAlias = "SecurityPermission";
        DynamicViewEntity dynamicViewEntity = new DynamicViewEntity();
        dynamicViewEntity.addMemberEntity(userAndSecurityAlias, "UserLoginAndSecurityGroup");
        dynamicViewEntity.addMemberEntity(securityPermissionAlias, "SecurityGroupPermission");

        dynamicViewEntity.addAlias(userAndSecurityAlias, userAndSecurityAlias + "userLoginId", "userLoginId", null, null, null, null);
        dynamicViewEntity.addAlias(userAndSecurityAlias, userAndSecurityAlias + "groupId", "groupId", null, null, null, null);
        dynamicViewEntity.addAlias(userAndSecurityAlias, userAndSecurityAlias + "enabled", "enabled", null, null, null, null);
        dynamicViewEntity.addAlias(securityPermissionAlias, securityPermissionAlias + "groupId", "groupId", null, null, null, null);
        dynamicViewEntity.addAlias(securityPermissionAlias, securityPermissionAlias + "permissionId", "permissionId", null, null, null, null);

        dynamicViewEntity.addViewLink(userAndSecurityAlias, securityPermissionAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("groupId"));

        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(EntityCondition.makeCondition(userAndSecurityAlias + "userLoginId", userLoginId));
        //conditions.add(EntityCondition.makeCondition(userAndSecurityAlias + "enabled", "Y"));
        conditions.add(EntityCondition.makeCondition(securityPermissionAlias + "permissionId", EntityOperator.LIKE, EASYPOS_APP_PERMISSION_PREFIX + "%"));

        List<GenericValue> queryResult = EntityQuery.use(delegator).from(dynamicViewEntity).where(conditions).cache(false).queryList();
        Set<String> permissions = new HashSet<>();
        for (GenericValue value : queryResult) {
            permissions.add((String) value.get(securityPermissionAlias + "permissionId"));
        }

        conditions.clear();
        conditions.add(EntityCondition.makeCondition("partyId", userPartyId));
        conditions.add(EntityCondition.makeCondition("roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID));
        long ownerQueryCount = EntityQuery.use(delegator).from("PartyRole").where(conditions).cache(false).queryCount();

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("permissions", Lists.newArrayList(permissions));
        returnedValues.put("success", true);
        returnedValues.put("isOwner", (ownerQueryCount > 0) ? true : false);

        return returnedValues;
    }
}
