package org.easypos.order.sales;

import javolution.util.FastMap;
import org.easypos.store.EasyPosProductStoreWorker;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;

import java.util.Map;

public class EasyPosOrderDefaultParamWorker {

    public static Map<String, Object> getAllOrderDefaultParams(DispatchContext dctx,
                                                           Map<String, ? extends Object> context) {
        Map<String,String> defaultCreateSalesOrderParams = FastMap.newInstance();
        defaultCreateSalesOrderParams.put("finalizeMode", "type");
        defaultCreateSalesOrderParams.put("orderMode", "SALES_ORDER");
        defaultCreateSalesOrderParams.put("hasAgreements", "N");
        defaultCreateSalesOrderParams.put("shipping_method", "NO_SHIPPING@_NA_");
        defaultCreateSalesOrderParams.put("may_split", "false");
        defaultCreateSalesOrderParams.put("checkOutPaymentId", "EXT_COD");
        defaultCreateSalesOrderParams.put("is_gift", "false");

        Map<String,String> defaultCompleteSalesOrderParams = FastMap.newInstance();
        defaultCompleteSalesOrderParams.put("setItemStatus", "Y");
        defaultCompleteSalesOrderParams.put("invoicePerShipment", "N");

        Map<String,String> defaultCreateInventoryItemsParams = FastMap.newInstance();
        defaultCreateInventoryItemsParams.put("inventoryItemTypeId", "NON_SERIAL_INV_ITEM");
        defaultCreateInventoryItemsParams.put("quantityRejected", "0");
        defaultCreateInventoryItemsParams.put("unitCost", "0");

        Map<String,String> defaultCreateCustomerParams = FastMap.newInstance();
        defaultCreateCustomerParams.put("require_email", "false");
        defaultCreateCustomerParams.put("require_phone", "false");
        defaultCreateCustomerParams.put("require_login", "false");
        defaultCreateCustomerParams.put("roleTypeId", EasyPosProductStoreWorker.CUSTOMER_ROLE_TYPE_ID);
        defaultCreateCustomerParams.put("USE_ADDRESS", "false");
        defaultCreateCustomerParams.put("USER_EMAIL", "test1234@outlook.com");

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("defaultCreateSalesOrderParams", defaultCreateSalesOrderParams);
        returnedValues.put("defaultCompleteSalesOrderParams", defaultCompleteSalesOrderParams);
        returnedValues.put("defaultCreateInventoryItemsParams", defaultCreateInventoryItemsParams);
        returnedValues.put("defaultCreateCustomerParams", defaultCreateCustomerParams);

        return returnedValues;
    }
}
