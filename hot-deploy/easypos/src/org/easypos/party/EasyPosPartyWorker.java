package org.easypos.party;

import org.ofbiz.base.util.Debug;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.ServiceUtil;

public class EasyPosPartyWorker {

    public static final String OWNER_ROLE_TYPE_ID = "OWNER";
    public static final String EMPLOYEE_ROLE_TYPE_ID = "EMPLOYEE";
    public static final String MANAGER_ROLE_TYPE_ID = "MANAGER";

    public static String getOwnerPartyId(GenericDelegator delegator, String ownerLoginId) throws GenericEntityException {
        GenericValue queryResult = EntityQuery.use(delegator).from("PartyAndUserLoginAndPerson").where("userLoginId", ownerLoginId).cache(true).queryOne();
        String partyId = (String) queryResult.get("partyId");
        Debug.log("Retrieve party id: " + partyId);

        return partyId;
    }
}
