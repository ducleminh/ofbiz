package org.easypos.request;

import javolution.util.FastMap;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.easypos.request.cache.RequestLRUCache;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ModelParam;
import org.ofbiz.service.ServiceUtil;

import java.util.Map;

public class EasyPosRequestStatusWorker {

    public static Map<String, Object> easyPOSCheckRequestStatus(DispatchContext dctx,
                                                       Map<String, ? extends Object> context) throws GenericServiceException {
        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Map<String, Object> responseObject = FastMap.newInstance();

        String requestId = (String) context.get("requestId");
        boolean requestSuccess = false;
        if (StringUtils.isNotEmpty(requestId)) {
            responseObject = RequestLRUCache.INSTANCE.get(RequestLRUCache.generateRequestId(username, requestId));
            if (responseObject != null) {
                requestSuccess = true;
            } else {
                responseObject = FastMap.newInstance();
            }
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("requestSuccess", requestSuccess);
        for (Map.Entry<String, Object> entry : responseObject.entrySet()) {
            ModelParam param = new ModelParam();
            param.name = entry.getKey();
            param.mode = "OUT";
            param.optional = false;

            dctx.getModelService("easyPOSCheckRequestStatus").addParam(param);
            dctx.getModelService("easyPOSCheckRequestStatus").validate = false;

            returnedValues.put(entry.getKey(), entry.getValue());
        }

        return returnedValues;
    }
}
