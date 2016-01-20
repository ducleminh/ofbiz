package org.easypos.order.sales;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import javolution.util.FastMap;
import org.easypos.product.EasyPosProductWorker;
import org.easypos.store.EasyPosProductStoreWorker;
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
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Date;

public class EasyPosSalesOrderWorker {

    public static final String PRODUCT_LIST_DELIMITER = ",";

    private static final String ORDER_APPROVED_STATUS = "ORDER_APPROVED";
    private static final String ORDER_ITEM_CANCELLED_STATUS = "ITEM_CANCELLED";
    private static final String CUSTOMER_ROLE_IN_ORDER = "BILL_TO_CUSTOMER";

    //private static final String GET_ALL_ORDER_PER_STORE_PER_CREATOR_SQL= "./hot-deploy/easypos/sql/GetAllOrderPerStorePerCreator.sql";

    private static final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

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
        List<String> orderStatusIdList = (List<String>) context.get("orderStatusId");

        Map<String, Order> orderIdToOrderMap = getOrders(
                delegator,
                orderTypeId,
                productStoreId,
                username,
                lowerBoundDate,
                upperBoundDate,
                orderStatusIdList);
        /*********************************************/


        //-----------------------------------------------------
        // Get all customers per order
        String partyAndPersonAlias = "PAP";
        String orderRoleAlias = "ORDERROLE";

        DynamicViewEntity dynamicViewEntity = new DynamicViewEntity();

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

        List<GenericValue> allCustomerOrderResult = EntityQuery.use(delegator).from(dynamicViewEntity).where(EntityCondition.makeCondition(conditions)).cache(true).queryList();
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

    public static String createNewEasyPosSalesOrder(HttpServletRequest request, HttpServletResponse response)
            throws IOException, GenericEntityException {

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

        request.setAttribute("orderDate", orderTimestamp.toLocalDateTime().format(dateTimeFormatter));
        request.setAttribute("seqIdMap", productIdToSeqIdMap);

        return "success";
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

        request.setAttribute("test", "test");

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

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("orderItems", orderItemList);

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
                                                String createdByUsername,
                                                Timestamp lowerBoundDate,
                                                Timestamp upperBoundDate,
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
        EntityCondition createdByCondition = EntityCondition.makeCondition(orderHeaderAlias + "createdBy", createdByUsername);
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

                String tableList = orderName.toLowerCase().replace("table", "");
                order.setTables(Arrays.asList(tableList.split(PRODUCT_LIST_DELIMITER)));

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
