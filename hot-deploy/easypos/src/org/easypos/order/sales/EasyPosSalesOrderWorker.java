package org.easypos.order.sales;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import javolution.util.FastMap;
import org.apache.commons.lang.StringUtils;
import org.easypos.product.EasyPosProductWorker;
import org.easypos.request.cache.RequestLRUCache;
import org.easypos.store.EasyPosProductStoreWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.model.DynamicViewEntity;
import org.ofbiz.entity.model.ModelKeyMap;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.order.OrderManagerEvents;
import org.ofbiz.order.shoppingcart.CheckOutEvents;
import org.ofbiz.order.shoppingcart.ShoppingCart;
import org.ofbiz.order.shoppingcart.ShoppingCartEvents;
import org.ofbiz.order.shoppingcart.ShoppingCartItem;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.ServiceContainer;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class EasyPosSalesOrderWorker {

    public static final String PRODUCT_LIST_DELIMITER = ",";

    private static final String ORDER_APPROVED_STATUS = "ORDER_APPROVED";
    private static final String ORDER_ITEM_CANCELLED_STATUS = "ITEM_CANCELLED";
    private static final String CUSTOMER_ROLE_IN_ORDER = "BILL_TO_CUSTOMER";

    //private static final String GET_ALL_ORDER_PER_STORE_PER_CREATOR_SQL= "./hot-deploy/easypos/sql/GetAllOrderPerStorePerCreator.sql";

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm");

    public static Map<String, Object> getAllOrdersByStoreByCreator(DispatchContext dctx,
                                                                   Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        String productStoreId = (String) context.get("productStoreId");
        String orderTypeId = (String) context.get("orderTypeId");
        Timestamp upperBoundDate = (Timestamp) context.get("upperBoundDate");
        Timestamp lowerBoundDate = (Timestamp) context.get("lowerBoundDate");
        String clientTimeZoneId = (String) context.get("timeZoneId");
        upperBoundDate = convertToTimeZone(upperBoundDate, clientTimeZoneId, TimeZone.getDefault().getID());
        lowerBoundDate = convertToTimeZone(lowerBoundDate, clientTimeZoneId, TimeZone.getDefault().getID());

        List<String> orderStatusIdList = (List<String>) context.get("orderStatusId");

        //////////////////////////////////////////////////////////
        //Get all owner, manager and employee login of this product store
        String productStoreRoleAlias = "ProductStoreRole";
        String userLoginAlias = "PartyAndUserLogin";
        DynamicViewEntity dynamicViewEntity = new DynamicViewEntity();

        dynamicViewEntity.addMemberEntity(productStoreRoleAlias, "ProductStoreRole");
        dynamicViewEntity.addMemberEntity(userLoginAlias, "PartyAndUserLogin");
        dynamicViewEntity.addAlias(productStoreRoleAlias, productStoreRoleAlias + "productStoreId", "productStoreId", null, null, null, null);
        dynamicViewEntity.addAlias(productStoreRoleAlias, productStoreRoleAlias + "partyId", "partyId", null, null, null, null);
        dynamicViewEntity.addAlias(userLoginAlias, userLoginAlias + "userLoginId", "userLoginId", null, null, null, null);

        dynamicViewEntity.addViewLink(userLoginAlias, productStoreRoleAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("partyId"));

        List<GenericValue> allCreatedByUsernamesResult = EntityQuery.use(delegator).from(dynamicViewEntity).where(productStoreRoleAlias + "productStoreId", productStoreId).cache(false).queryList();
        Set<String> allStoreEmployeeSet = new HashSet<>();
        for (GenericValue value : allCreatedByUsernamesResult) {
            if (value != null) {
                allStoreEmployeeSet.add((String) value.get(userLoginAlias + "userLoginId"));
            }
        }
        /*******************************************************/

        Map<String, Order> orderIdToOrderMap = getOrders(
                delegator,
                orderTypeId,
                productStoreId,
                allStoreEmployeeSet,
                lowerBoundDate,
                upperBoundDate,
                clientTimeZoneId,
                orderStatusIdList);
        /*********************************************/


        //-----------------------------------------------------
        // Get all customers per order
        String partyAndPersonAlias = "PAP";
        String orderRoleAlias = "ORDERROLE";

        dynamicViewEntity = new DynamicViewEntity();

        dynamicViewEntity.addMemberEntity(partyAndPersonAlias, "PartyAndPerson");
        dynamicViewEntity.addMemberEntity(orderRoleAlias, "OrderRole");

        dynamicViewEntity.addAlias(orderRoleAlias, orderRoleAlias + "orderId", "orderId", null, null, null, null);
        dynamicViewEntity.addAlias(orderRoleAlias, orderRoleAlias + "roleTypeId", "roleTypeId", null, null, null, null);
        dynamicViewEntity.addAlias(orderRoleAlias, orderRoleAlias + "partyId", "partyId", null, null, null, null);
        dynamicViewEntity.addAlias(partyAndPersonAlias, partyAndPersonAlias + "firstName", "firstName", null, null, null, null);
        dynamicViewEntity.addAlias(partyAndPersonAlias, partyAndPersonAlias + "lastName", "lastName", null, null, null, null);

        dynamicViewEntity.addViewLink(orderRoleAlias, partyAndPersonAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("partyId"));

        EntityCondition orderIdCondition = EntityCondition.makeCondition(orderRoleAlias + "orderId", EntityOperator.IN, orderIdToOrderMap.keySet());
        EntityCondition roleTypeIdCondition = EntityCondition.makeCondition(orderRoleAlias + "roleTypeId", CUSTOMER_ROLE_IN_ORDER);
        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(orderIdCondition);
        conditions.add(roleTypeIdCondition);

        List<GenericValue> allCustomerOrderResult = EntityQuery.use(delegator).from(dynamicViewEntity).where(EntityCondition.makeCondition(conditions)).cache(false).queryList();
        for (GenericValue value : allCustomerOrderResult) {
            String orderId = (String) value.get(orderRoleAlias + "orderId");
            String customerFirstName = (String) value.get(partyAndPersonAlias + "firstName");
            String customerLastName = (String) value.get(partyAndPersonAlias + "lastName");
            String customerId = (String) value.get(orderRoleAlias + "partyId");

            Customer customer = new Customer();
            customer.setId(customerId);
            customer.setName(EasyPosProductStoreWorker.createCustomerName(customerFirstName, customerLastName));

            Order orderToUpdate = orderIdToOrderMap.get(orderId);
            if (orderToUpdate != null) {
                orderToUpdate.setCustomer(customer);
            }
        }

        /*********************************************/

        ListMultimap<String, Order> statusToOrdersMap = ArrayListMultimap.create();
        for (Order order : orderIdToOrderMap.values()) {
            String status = order.getOrderStatus();
            statusToOrdersMap.put(status, order);
        }

        if (statusToOrdersMap.isEmpty()) {
            Order tempOrder = new Order();
            for (String status : orderStatusIdList) {
                statusToOrdersMap.put(status, tempOrder);
                statusToOrdersMap.get(status).clear();
            }
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("orders", statusToOrdersMap.asMap());

        return returnedValues;
    }

    public static String createAndCompleteEasyPosSalesOrder(HttpServletRequest request, HttpServletResponse response)
            throws IOException, GenericEntityException, GenericServiceException {
        GenericValue userLoginGenericValue = (GenericValue) request.getSession().getAttribute("userLogin");
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        String invoicePerShipment = request.getParameter("invoicePerShipment");
        String setItemStatus = request.getParameter("setItemStatus");
        String facilityId = request.getParameter("facilityId");
        String inventoryItemTypeId = request.getParameter("inventoryItemTypeId");
        String quantityRejected = request.getParameter("quantityRejected");
        String unitCost = request.getParameter("unitCost");
        String paymentPartyId = request.getParameter("paymentPartyId");

        String customerId = request.getParameter("customerId");
        String customerFirstName = request.getParameter("USER_FIRST_NAME");
        String customerLastName = request.getParameter("USER_LAST_NAME");
        String productStoreId = request.getParameter("PRODUCT_STORE_ID");
        String requireEmail = request.getParameter("require_email");
        String requirePhone = request.getParameter("require_phone");
        String requireLogin = request.getParameter("require_login");
        String roleTypeId = request.getParameter("roleTypeId");
        String useAddress = request.getParameter("USE_ADDRESS");
        String userEmail = request.getParameter("USER_EMAIL");

        String quickCompleteString = request.getParameter("quickComplete");
        List<String> productAndQuantity = Arrays.asList(request.getParameterMap().get("productAndQuantity"));

        Map<String, Object> paramMap;
        Map<String, Object> serviceResult;

        //Create new customer if required
        if (StringUtils.isBlank(customerId)) {
            Debug.logInfo("New sales order: creating a new customer", "createAndCompleteEasyPosSalesOrder");

            paramMap = UtilMisc.toMap(
                    "PRODUCT_STORE_ID", productStoreId,
                    "USER_FIRST_NAME", customerFirstName,
                    "USER_LAST_NAME", customerLastName,
                    "require_email", requireEmail,
                    "require_phone", requirePhone,
                    "require_login", requireLogin,
                    "roleTypeId", roleTypeId,
                    "USE_ADDRESS", useAddress,
                    "USER_EMAIL", userEmail,
                    "userLogin", userLoginGenericValue
            );
            serviceResult = dispatcher.runSync("easyPOSCreateNewCustomer", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                throw new GenericServiceException("Unable to create new customer");
            }

            customerId = (String) request.getAttribute("partyId");
        }

        request.setAttribute("partyId", customerId);
        request.setAttribute("billToCustomerPartyId", customerId);
        /***************************************************/

        //Create new sales order
        String result = createNewEasyPosSalesOrder(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, result)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        String orderId = (String) request.getAttribute("orderId");
        /***************************************************/

        boolean quickComplete = false;
        if (StringUtils.isNotBlank(quickCompleteString)) {
            quickComplete = Boolean.valueOf(quickCompleteString);
        }
        if (!quickComplete) {
            Debug.logInfo("We don't quick complete sales order, return.", "createAndCompleteEasyPosSalesOrder");
            return ResponseConstants.RESPONSE_SUCCESS;
        }

        //Create the payment for order
        request.setAttribute("partyId", paymentPartyId);
        result = receiveOfflinePaymentForSalesOrder(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, result)) {
            return ResponseConstants.RESPONSE_ERROR;
        }

        response.addHeader("success", "false");
        /***************************************************/

        //Now create the invetory items
        paramMap = UtilMisc.toMap(
                "facilityId", facilityId,
                "productAndQuantity", productAndQuantity,
                "inventoryItemTypeId", inventoryItemTypeId,
                "quantityRejected", quantityRejected,
                "unitCost", unitCost,
                "userLogin", userLoginGenericValue
        );
        serviceResult = dispatcher.runSync("createInventoryItems", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            throw new GenericServiceException("Unable to create inventory items");
        }
        /***************************************************/

        //create success, now complete the order
        paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "invoicePerShipment", invoicePerShipment,
                "facilityId", facilityId,
                "setItemStatus", setItemStatus,
                "userLogin", userLoginGenericValue
        );
        /***************************************************/

        serviceResult = dispatcher.runSync("completeSalesOrder", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            throw new GenericServiceException("Unable to complete sales order");
        }

        return ResponseConstants.RESPONSE_SUCCESS;
    }

    public static String createNewEasyPosSalesOrder(HttpServletRequest request, HttpServletResponse response)
            throws IOException, GenericEntityException, GenericServiceException {

        String javaEventResult = ShoppingCartEvents.destroyCart(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }

        //---------------------SET order mode and select store---------------
        javaEventResult = ShoppingCartEvents.initializeOrderEntry(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        /***************************************************/

        //---------------------SET currency, order name and menu---------------
        javaEventResult = ShoppingCartEvents.setOrderCurrencyAgreementShipDates(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        /***************************************************/

        //---------------------add order items---------------
        Map<String, String> productAndQuantityMap = getProductIdAndQuantityFromParam(Arrays.asList(request.getParameterMap().get("productAndQuantity")));

        for (Map.Entry<String, String> productAndQuantity : productAndQuantityMap.entrySet()) {
            request.setAttribute("add_product_id", productAndQuantity.getKey());
            request.setAttribute("quantity", productAndQuantity.getValue());

            javaEventResult = ShoppingCartEvents.addToCart(request, response);
            if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
                return ResponseConstants.RESPONSE_ERROR;
            }
        }
        /***************************************************/

        //---------------------TIME TO CHECKOUT---------------
        javaEventResult = CheckOutEvents.setQuickCheckOutOptions(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        /***************************************************/

        //---------------------NOW CREATE THE ORDER---------------
        javaEventResult = CheckOutEvents.createOrder(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        /***************************************************/

        String orderId = (String) request.getAttribute("orderId");
        Map<String,String> productIdToSeqIdMap = FastMap.newInstance();
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        List<GenericValue> queryResult = EntityQuery.use(delegator).from("OrderItem").where("orderId", orderId).cache(false).queryList();
        for (GenericValue value : queryResult) {
            productIdToSeqIdMap.put((String) value.get("productId"), (String) value.get("orderItemSeqId"));
        }

        GenericValue orderHeaderResult = EntityQuery.use(delegator).from("OrderHeader").where("orderId", orderId).cache(false).queryOne();
        Timestamp orderTimestamp = (Timestamp) orderHeaderResult.get("orderDate");
        String formattedDateTime = orderTimestamp.toLocalDateTime().format(dateTimeFormatter);
        String timeZoneId = TimeZone.getDefault().getID();
        Debug.logInfo("timezoneId: " + timeZoneId, "EasyPosSalesOrderWorker");

        request.setAttribute("orderDate", formattedDateTime);
        request.setAttribute("timeZoneId", timeZoneId);
        request.setAttribute("seqIdMap", productIdToSeqIdMap);

        String[] requestIds = request.getParameterMap().get("requestId");
        if (requestIds != null && requestIds.length > 0) {
            Map<String, Object> responseResult = FastMap.newInstance();
            responseResult.put("orderDate", formattedDateTime);
            responseResult.put("timeZoneId", timeZoneId);
            responseResult.put("seqIdMap", productIdToSeqIdMap);
            responseResult.put("orderId", orderId);

            RequestLRUCache.INSTANCE.put(RequestLRUCache.generateRequestId(request.getParameter("userLoginId"), requestIds[0]), responseResult);
        }

        return ResponseConstants.RESPONSE_SUCCESS;
    }

    public static Map<String, Object> cancelSalesOrder(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        String orderId = (String) context.get("orderId");
        String statusId = (String) context.get("statusId");
        String setItemStatus = (String) context.get("setItemStatus");

        Map<String, Object> serviceResult;

        //-----------------------------------------------------
        // Add category to menu
        Map<String, Object> paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "statusId", statusId,
                "setItemStatus", setItemStatus,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("changeOrderStatus", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to cancel order: " + orderId);
        }
        /*********************************************/

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }

    public static Map<String, Object> createInventoryItems(DispatchContext dctx,
                                                       Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);
        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        String facilityId = (String) context.get("facilityId");
        String inventoryItemTypeId = (String) context.get("inventoryItemTypeId");
        List<String> productAndQuantity = (List) context.get("productAndQuantity");
        String quantityRejected = (String) context.get("quantityRejected");
        String unitCost = (String) context.get("unitCost");

        Map<String, Object> paramMap;
        Map<String, Object> serviceResult;

        //-----------------------------------------------------
        // create inventory for each product
        Map<String, String> productAndQuantityMap = getProductIdAndQuantityFromParam(productAndQuantity);

        for (Map.Entry<String,String> entry : productAndQuantityMap.entrySet()) {
            paramMap = UtilMisc.toMap(
                    "facilityId", facilityId,
                    "productId", entry.getKey(),
                    "inventoryItemTypeId", inventoryItemTypeId,
                    "quantityAccepted", entry.getValue(),
                    "quantityRejected", quantityRejected,
                    "unitCost", unitCost,
                    "datetimeReceived", new Timestamp( (new Date()).getTime()),
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("receiveInventoryProduct", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add inventory item for product: " + entry.getKey());
            }
        }
        /*********************************************/

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }

    public static String completePaymentInventoryAndOrder(HttpServletRequest request, HttpServletResponse response)
            throws IOException, GenericEntityException, GenericServiceException {
        GenericValue userLoginGenericValue = (GenericValue) request.getSession().getAttribute("userLogin");
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        String facilityId = request.getParameter("facilityId");
        String inventoryItemTypeId = request.getParameter("inventoryItemTypeId");
        String quantityRejected = request.getParameter("quantityRejected");
        String unitCost = request.getParameter("unitCost");
        String orderId = request.getParameter("orderId");
        String invoicePerShipment = request.getParameter("invoicePerShipment");
        String setItemStatus = request.getParameter("setItemStatus");
        List<String> productAndQuantity = Arrays.asList(request.getParameterMap().get("productAndQuantity"));

        Map<String, Object> paramMap;
        Map<String, Object> serviceResult;

        //Create order payment
        String result = receiveOfflinePaymentForSalesOrder(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, result)) {
            return ResponseConstants.RESPONSE_ERROR;
        }
        response.addHeader("success", "false");
        /***************************************************/

        //Now create the invetory items
        paramMap = UtilMisc.toMap(
                "facilityId", facilityId,
                "productAndQuantity", productAndQuantity,
                "inventoryItemTypeId", inventoryItemTypeId,
                "quantityRejected", quantityRejected,
                "unitCost", unitCost,
                "userLogin", userLoginGenericValue
        );
        serviceResult = dispatcher.runSync("createInventoryItems", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            throw new GenericServiceException("Unable to create inventory items");
        }
        /***************************************************/

        //create success, now complete the order
        paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "invoicePerShipment", invoicePerShipment,
                "facilityId", facilityId,
                "setItemStatus", setItemStatus,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("completeSalesOrder", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            throw new GenericServiceException("Unable to complete sales order");
        }
        /***************************************************/

        request.setAttribute("success", true);

        return ResponseConstants.RESPONSE_SUCCESS;
    }

    public static Map<String, Object> completeSalesOrder(DispatchContext dctx,
                                                       Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        String facilityId = (String) context.get("facilityId");
        String setItemStatus = (String) context.get("setItemStatus");
        String orderId = (String) context.get("orderId");
        String invoicePerShipment = (String) context.get("invoicePerShipment");

        Map<String, Object> serviceResult;
        Map<String, Object> paramMap;

        //-----------------------------------------------------
        // Approve order first
        paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "statusId", ORDER_APPROVED_STATUS,
                "setItemStatus", setItemStatus,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("changeOrderStatus", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to approve order: " + orderId);
        }
        /*********************************************/

        //-----------------------------------------------------
        // Update invoicePerShipment for order
        paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "invoicePerShipment", invoicePerShipment,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("updateOrderHeader", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to update invoicePerShipment for order: " + orderId);
        }
        /*********************************************/

        //-----------------------------------------------------
        // quick deliver the entire order
        paramMap = UtilMisc.toMap(
                "orderId", orderId,
                "originFacilityId", facilityId,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("quickShipEntireOrder", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to quick ship entire order: " + orderId);
        }

        /*********************************************/

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }

    public static Map<String, Object> updateItemStatusSalesOrder(DispatchContext dctx,
                                                         Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        String orderId = (String) context.get("orderId");
        List<String> itemSeqIds = (List<String>) context.get("orderItemSeqAndStatusId");

        Map<String, Object> serviceResult;
        Map<String, Object> paramMap;

        //-----------------------------------------------------
        // Update invoicePerShipment for order
        for (String itemSeqAndStatusId : itemSeqIds) {
            String[] tokens = itemSeqAndStatusId.split(PRODUCT_LIST_DELIMITER);

            paramMap = UtilMisc.toMap(
                    "orderId", orderId,
                    "orderItemSeqId", tokens[0],
                    "statusId", tokens[1],
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("changeOrderItemStatus", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to update item status for seqId: " + tokens[0] + ", " + tokens[1]);
            }
        }
        /*********************************************/

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;

    }

    public static String receiveOfflinePaymentForSalesOrder(HttpServletRequest request, HttpServletResponse response)
        throws IOException {

        String javaEventResult = OrderManagerEvents.receiveOfflinePayment(request, response);
        if (Objects.equals(ResponseConstants.RESPONSE_ERROR, javaEventResult)) {
            return ResponseConstants.RESPONSE_ERROR;
        }

        response.addHeader("success", "true");

        return "success";
    }

    public static Map<String, Object> appendItemToExistingOrder(DispatchContext dctx,
                                                                Map<String, ? extends Object> context)
            throws GenericServiceException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        String orderId = (String) context.get("orderId");
        List<String> productAndQuantityList = (List<String>) context.get("productAndQuantity");

        String facilityId = (String) context.get("facilityId");
        String inventoryItemTypeId = (String) context.get("inventoryItemTypeId");
        String quantityRejected = (String) context.get("quantityRejected");
        String unitCost = (String) context.get("unitCost");

        Map<String, Object> serviceResult;
        Map<String, Object> paramMap;
        List<OrderItem> orderItemList = new ArrayList<>();

        GenericValue queryResult = EntityQuery.use(delegator).from("OrderItemShipGroupAssoc").where("orderId", orderId).cache(false).queryFirst();
        String shipGroupSeqId = (String) queryResult.get("shipGroupSeqId");

        //-----------------------------------------------------
        // Update invoicePerShipment for order
        for (String productAndQuantity : productAndQuantityList) {
            String[] tokens = productAndQuantity.split(PRODUCT_LIST_DELIMITER);
            String productId = tokens[0];
            BigDecimal quantity = new BigDecimal(tokens[1]);

            paramMap = UtilMisc.toMap(
                    "orderId", orderId,
                    "shipGroupSeqId", shipGroupSeqId,
                    "productId", productId,
                    "quantity", quantity,
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("appendOrderItem", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to append item to order: " + productId + ", " + quantity);
            }

            ShoppingCart cart = (ShoppingCart) serviceResult.get("shoppingCart");
            ShoppingCartItem cartItem = cart.findCartItem(cart.size() - 1);

            OrderItem orderItem = new OrderItem();
            orderItem.setOrderSeqId(cartItem.getOrderItemSeqId());
            EasyPosProductWorker.Product product = new EasyPosProductWorker.Product();
            product.setId(productId);
            orderItem.setProduct(product);
            orderItem.setItemStatus(cartItem.getStatusId());
            orderItem.setQuantity(quantity.intValue());
            orderItem.setOrderSeqId(cartItem.getOrderItemSeqId());

            orderItemList.add(orderItem);


        }
        /*********************************************/

        // create inventory items for the newly added product
        paramMap = UtilMisc.toMap(
                "facilityId", facilityId,
                "inventoryItemTypeId", inventoryItemTypeId,
                "quantityRejected", quantityRejected,
                "unitCost", unitCost,
                "productAndQuantity", productAndQuantityList,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createInventoryItems", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable create inventory items for newly created items");
        }

        /*********************************************/

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("orderItems", orderItemList);
        returnedValues.put("success", true);

        return returnedValues;
    }

    private static Map<String,String> getProductIdAndQuantityFromParam(Collection<String> productAndQuantityList) {
        Map<String,String> resultMap = new HashMap<>();

        for (String productAndQuantity : productAndQuantityList) {
            if (productAndQuantity.contains(PRODUCT_LIST_DELIMITER)) {
                String[] tokens = productAndQuantity.split(PRODUCT_LIST_DELIMITER);
                resultMap.put(tokens[0], tokens[1]);
            }
        }

        return resultMap;
    }

    private static Map<String, Order> getOrders(GenericDelegator delegator,
                                                String orderTypeId,
                                                String productStoreId,
                                                Collection<String> createdByUsernames,
                                                Timestamp lowerBoundDate,
                                                Timestamp upperBoundDate,
                                                String clientTimeZoneId,
                                                List<String> orderStatusIdList) throws GenericEntityException {
        String orderHeaderAlias = "OH";
        String orderItemAlias = "OI";
        String productAlias = "P";
        String productPriceAlias = "PP";

        DynamicViewEntity allOrdersEntity = new DynamicViewEntity();
        allOrdersEntity.addMemberEntity(orderHeaderAlias, "OrderHeader");
        allOrdersEntity.addMemberEntity(orderItemAlias, "OrderItem");
        allOrdersEntity.addMemberEntity(productAlias, "Product");
        allOrdersEntity.addMemberEntity(productPriceAlias, "ProductPrice");

        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "orderId", "orderId", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "statusId", "statusId", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "orderName", "orderName", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "createdBy", "createdBy", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "orderDate", "orderDate", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "orderTypeId", "orderTypeId", null, null, null, null);
        allOrdersEntity.addAlias(orderHeaderAlias, orderHeaderAlias + "productStoreId", "productStoreId", null, null, null, null);
        allOrdersEntity.addAlias(orderItemAlias, orderItemAlias + "orderItemSeqId", "orderItemSeqId", null, null, null, null);
        allOrdersEntity.addAlias(orderItemAlias, orderItemAlias + "statusId", "statusId", null, null, null, null);
        allOrdersEntity.addAlias(orderItemAlias, orderItemAlias + "productId", "productId", null, null, null, null);
        allOrdersEntity.addAlias(orderItemAlias, orderItemAlias + "quantity", "quantity", null, null, null, null);
        allOrdersEntity.addAlias(productAlias, productAlias + "internalName", "internalName", null, null, null, null);
        allOrdersEntity.addAlias(productPriceAlias, productPriceAlias + "currencyUomId", "currencyUomId", null, null, null, null);
        allOrdersEntity.addAlias(productPriceAlias, productPriceAlias + "price", "price", null, null, null, null);

        allOrdersEntity.addViewLink(orderHeaderAlias, orderItemAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("orderId"));
        allOrdersEntity.addViewLink(orderItemAlias, productAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("productId"));
        allOrdersEntity.addViewLink(orderItemAlias, productPriceAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("productId"));

        EntityCondition orderTypeCondition = EntityCondition.makeCondition(orderHeaderAlias + "orderTypeId", orderTypeId);
        EntityCondition productStoreCondition = EntityCondition.makeCondition(orderHeaderAlias + "productStoreId", productStoreId);
        EntityCondition createdByCondition = EntityCondition.makeCondition(orderHeaderAlias + "createdBy", EntityOperator.IN, createdByUsernames);
        EntityCondition orderItemStatusNotCancelledCondition = EntityCondition.makeCondition(orderHeaderAlias + "statusId", EntityOperator.NOT_EQUAL, ORDER_ITEM_CANCELLED_STATUS);
        EntityCondition orderHeaderStatusCondition = EntityCondition.makeCondition(orderHeaderAlias + "statusId", EntityOperator.IN, orderStatusIdList);
        EntityCondition orderHeaderDateLowerBoundCondition = EntityCondition.makeCondition(orderHeaderAlias + "orderDate", EntityOperator.GREATER_THAN_EQUAL_TO, lowerBoundDate);
        EntityCondition orderHeaderDateUpperBoundCondition = EntityCondition.makeCondition(orderHeaderAlias + "orderDate", EntityOperator.LESS_THAN_EQUAL_TO, upperBoundDate);

        List<EntityCondition> allOrderConditions = new ArrayList<>();
        allOrderConditions.add(orderTypeCondition);
        allOrderConditions.add(productStoreCondition);
        allOrderConditions.add(orderItemStatusNotCancelledCondition);
        allOrderConditions.add(orderHeaderStatusCondition);
        allOrderConditions.add(orderHeaderDateLowerBoundCondition);
        allOrderConditions.add(orderHeaderDateUpperBoundCondition);
        allOrderConditions.add(createdByCondition);

        List<GenericValue> allOrdersResult = EntityQuery.use(delegator).from(allOrdersEntity).where(EntityCondition.makeCondition(allOrderConditions)).cache(false).queryList();

        Map<String, Order> orderIdToOrderMap = new HashMap<>();
        for (GenericValue result : allOrdersResult) {
            String orderId = (String) result.get(orderHeaderAlias + "orderId");
            String orderStatusId = (String) result.get(orderHeaderAlias + "statusId");
            String orderName = (String) result.get(orderHeaderAlias + "orderName");
            Timestamp orderTimeStamp = (Timestamp) result.get(orderHeaderAlias + "orderDate");
            String orderItemSeqId = (String) result.get(orderItemAlias + "orderItemSeqId");
            String orderItemStatusId = (String) result.get(orderItemAlias + "statusId");
            String productId = (String) result.get(orderItemAlias + "productId");
            String productName = (String) result.get(productAlias + "internalName");
            BigDecimal productPrice = (BigDecimal) result.get(productPriceAlias + "price");
            String currency = (String) result.get(productPriceAlias + "currencyUomId");
            BigDecimal quantity = (BigDecimal) result.get(orderItemAlias + "quantity");
            String createdBy = (String) result.get(orderHeaderAlias + "createdBy");

            OrderItem item = new OrderItem();
            item.setItemStatus(orderItemStatusId);
            item.setOrderSeqId(orderItemSeqId);
            item.setQuantity(quantity.intValue());
            EasyPosProductWorker.Product product = new EasyPosProductWorker.Product();
            product.setId(productId);
            product.setPrice(productPrice.floatValue());
            product.setName(productName);
            product.setCurrencySymbol(currency);
            item.setProduct(product);

            List<OrderItem> orderItems;
            if (orderIdToOrderMap.containsKey(orderId)) {
                orderItems = orderIdToOrderMap.get(orderId).getOrderItems();
                orderItems.add(item);
            } else {
                Order order = new Order();
                order.setId(orderId);
                order.setOrderStatus(orderStatusId);
                order.setCreatedByLoginId(createdBy);

                String tableList = orderName.toLowerCase().replace("table", "");
                order.setTables(Arrays.asList(tableList.split(PRODUCT_LIST_DELIMITER)));

                orderTimeStamp = convertToTimeZone(orderTimeStamp, TimeZone.getDefault().getID(), clientTimeZoneId);
                LocalDateTime localDate = orderTimeStamp.toLocalDateTime();
                order.setTimestamp(dateTimeFormatter.format(localDate));

                orderItems = new ArrayList<>();
                orderItems.add(item);
                order.setOrderItems(orderItems);

                orderIdToOrderMap.put(orderId, order);
            }
        }

        return orderIdToOrderMap;
    }

    private static Timestamp convertToTimeZone(Timestamp timeStamp, String fromTimeZoneId, String ToTimeZoneId) {
        ZonedDateTime newDateTime = timeStamp.toLocalDateTime().atZone(ZoneId.of(fromTimeZoneId)).withZoneSameInstant(ZoneId.of(ToTimeZoneId));

        return Timestamp.valueOf(newDateTime.toLocalDateTime());
    }

    private static class OrderItem {
        private EasyPosProductWorker.Product product;
        private int quantity;
        private String note;
        private String orderSeqId;
        private String itemStatus;

        public EasyPosProductWorker.Product getProduct() {
            return product;
        }

        public void setProduct(EasyPosProductWorker.Product product) {
            this.product = product;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public String getOrderSeqId() {
            return orderSeqId;
        }

        public void setOrderSeqId(String orderSeqId) {
            this.orderSeqId = orderSeqId;
        }

        public String getItemStatus() {
            return itemStatus;
        }

        public void setItemStatus(String itemStatus) {
            this.itemStatus = itemStatus;
        }
    }

    private static class Order {
        private String id;
        private String orderStatus;
        private List<String> tables;
        private Customer customer;
        private String timestamp;
        private List<OrderItem> orderItems;
        private String createdByLoginId;

        public String getCreatedByLoginId() {
            return createdByLoginId;
        }

        public void setCreatedByLoginId(String createdByLoginId) {
            this.createdByLoginId = createdByLoginId;
        }

        public List<OrderItem> getOrderItems() {
            return orderItems;
        }

        public void setOrderItems(List<OrderItem> orderItems) {
            this.orderItems = orderItems;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getOrderStatus() {
            return orderStatus;
        }

        public void setOrderStatus(String orderStatus) {
            this.orderStatus = orderStatus;
        }

        public List<String> getTables() {
            return tables;
        }

        public void setTables(List<String> tables) {
            this.tables = tables;
        }

        public Customer getCustomer() {
            return customer;
        }

        public void setCustomer(Customer customer) {
            this.customer = customer;
        }

        public String getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(String timestamp) {
            this.timestamp = timestamp;
        }
    }

    private static class Customer {
        private String id;
        private String name;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

}
