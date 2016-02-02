package org.easypos.store;

import javolution.util.FastMap;
import org.apache.commons.io.FileUtils;
import org.easypos.category.EasyPosProductTagWorker;
import org.easypos.party.EasyPosPartyWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.StringUtil;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.jdbc.SQLProcessor;
import org.ofbiz.entity.model.DynamicViewEntity;
import org.ofbiz.entity.model.ModelKeyMap;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.GenericServiceException;


import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

public class EasyPosProductStoreWorker {

    private static final String GET_STORENAME_PER_OWNER_SQL= "./hot-deploy/easypos/sql/GetStoreNamePerOwner.sql";
    private static final String GET_DEFAULT_FACILITY_PER_OWNER_SQL= "./hot-deploy/easypos/sql/GetDefaultFacilityPerOwner.sql";

    public static final String CUSTOMER_ROLE_TYPE_ID = "CUSTOMER";
    public static final String CONTACT_MECH_TYPE_ID = "POSTAL_ADDRESS";

    public static Map<String, Object> createNewCompleteStore(DispatchContext dctx,
                                                                       Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException, GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        String storeName = ((String) context.get("storeName")).toUpperCase();
        String storeDefaultCurency = ((String) context.get("storeDefaultCurrency")).toUpperCase();
        String menuName = ((String) context.get("menuName")).toUpperCase();
        String facilityId = (String) context.get("facilityId");
        List<String> tableIds = (List<String>) context.get("tableIdList");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");
        String partyId = (String) userLoginGenericValue.get("partyId");

        Map<String, Object> serviceResult;
        String productStoreId = null;
        String prodCatalogId = null;
        String newCategoryId = null;
        Map<String, Object> returnedValues;

        //-----------------------------------------------------
        //Check if store already existed
        serviceResult = getAllStoresPerOwner(dctx, context);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Fail to check if store existed: " + storeName);
        }

        List<Store> allStores = (List<Store>) serviceResult.get("stores");
        for (Store store : allStores) {
            if (storeName.equalsIgnoreCase(store.getName())) {
                returnedValues = ServiceUtil.returnSuccess();
                returnedValues.put("storeId", store.getId());
                returnedValues.put("storeName", storeName);
                returnedValues.put("storeExisted", true);

                return returnedValues;
            }
        }
        /*********************************************/

        //-----------------------------------------------------
        //Create new product store
        Map<String, Object> paramMap = UtilMisc.toMap(
                "storeName", storeName,
                "checkInventory", "Y",
                "reserveInventory", "Y",
                "requireInventory", "N",
                "defaultCurrencyUomId", storeDefaultCurency,
                "payToPartyId", partyId,
                "userLogin", userLoginGenericValue
        );
        serviceResult = dispatcher.runSync("createProductStore", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create new store: " + storeName);
        }
        productStoreId = (String) serviceResult.get("productStoreId");
        partyId = EasyPosPartyWorker.getOwnerPartyId(delegator, username);
        /*********************************************/

        //-----------------------------------------------------
        //ADD FACILITY TO STORE
        paramMap = UtilMisc.toMap(
                "productStoreId", productStoreId,
                "facilityId", facilityId,
                "fromDate", new Timestamp( (new Date()).getTime()),
                "userLogin", userLoginGenericValue
        );
        serviceResult = dispatcher.runSync("createProductStoreFacility", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable add facility to store: " + storeName);
        }
        /*********************************************/

        //-----------------------------------------------------
        // param map for creating product store role, set user to the owner of the store
        paramMap = UtilMisc.toMap(
                "partyId", partyId,
                "roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID,
                "productStoreId", productStoreId,
                "fromDate", new Timestamp( (new Date()).getTime()),
                "userLogin", userLoginGenericValue
        );
        serviceResult = dispatcher.runSync("createProductStoreRole", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create product store role");
        }
        /*********************************************/

        //-----------------------------------------------------
        // create store's own catalog
        paramMap = UtilMisc.toMap(
                "catalogName", menuName,
                "useQuickAdd", "Y",
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProdCatalog", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create the new store's menu: " + menuName);
        }
        prodCatalogId = (String) serviceResult.get("prodCatalogId");
        /*********************************************/

        //-----------------------------------------------------
        // set menu's owner
        paramMap = UtilMisc.toMap(
                "partyId", partyId,
                "roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID,
                "prodCatalogId", prodCatalogId,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addProdCatalogToParty", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to set menu's owner");
        }
        /*********************************************/

        //-----------------------------------------------------
        // create the category which will have the same name as menu
        Map<String, Object> createNewCategoryParamMap = UtilMisc.toMap(
                "productCategoryTypeId", EasyPosProductTagWorker.INTERNAL_CATEGORY,
                "categoryName", menuName,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProductCategory", createNewCategoryParamMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create new category: " + menuName);
        }
        newCategoryId = (String) serviceResult.get("productCategoryId");
        /*********************************************/

        //-----------------------------------------------------
        // Add category to menu
        Map<String, Object> addCategoryToCatalogParamMap = UtilMisc.toMap(
                "prodCatalogId", prodCatalogId,
                "productCategoryId", newCategoryId,
                "prodCatalogCategoryTypeId", EasyPosProductTagWorker.DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addProductCategoryToProdCatalog", addCategoryToCatalogParamMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add new category to menu: " + menuName);
        }
        /*********************************************/

        //-----------------------------------------------------
        // Set category's owner
        Map<String, Object> setCategoryOwnerParamMap = UtilMisc.toMap(
                "partyId", partyId,
                "productCategoryId", newCategoryId,
                "roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addPartyToCategory", setCategoryOwnerParamMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add party to category");
        }
        /*********************************************/

        //-----------------------------------------------------
        // add catalog to store
        paramMap = UtilMisc.toMap(
                "productStoreId", productStoreId,
                "prodCatalogId", prodCatalogId,
                "fromDate", new Timestamp( (new Date()).getTime()),
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProductStoreCatalog", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create product store catalog relationship");
        }
        /*********************************************/

        //-----------------------------------------------------
        // add tables to store
        paramMap = UtilMisc.toMap(
                "productStoreId", productStoreId,
                "tableIdList", tableIds,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addTablesToProductStore", paramMap);
        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add tables to product store");
        }
        /*********************************************/

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("storeId", productStoreId);
        returnedValues.put("storeName", storeName);
        returnedValues.put("menuId", prodCatalogId);
        returnedValues.put("menuName", menuName);
        returnedValues.put("categoryId", newCategoryId);
        returnedValues.put("defaultCurrency", storeDefaultCurency);
        returnedValues.put("storeExisted", false);

        return returnedValues;
    }

    public static Map<String, Object> addTablesToProductStore(DispatchContext dctx,
                                                           Map<String, ? extends Object> context)
            throws GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        List<String> tableIds = (List<String>) context.get("tableIdList");
        String productStoreId = (String) context.get("productStoreId");

        //remove all rows per store id first
        String entityName = "EasyPosProductStoreTable";

        EntityCondition productStoreIdCondition = EntityCondition.makeCondition("productStoreId", productStoreId);
        delegator.removeByCondition(entityName, productStoreIdCondition);

        for (String tableId : tableIds) {
            if (tableId != null && tableId.length() > 0) {
                Map<String, Object> fields = FastMap.newInstance();
                fields.put("productStoreId", productStoreId);
                fields.put("tableName", tableId);
                fields.put("fromDate", new Timestamp( (new Date()).getTime()));

                delegator.create(entityName, fields);
            }
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("success", true);

        return returnedValues;
    }

    public static Map<String, Object> getAllStoresPerOwner(DispatchContext dctx,
                                                          Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {

        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String ownerPartyId = (String) userLoginGenericValue.get("partyId");


        List<EntityCondition> queryConditions = new ArrayList<>();
        queryConditions.add(EntityCondition.makeCondition("partyId", ownerPartyId));
        queryConditions.add(EntityCondition.makeCondition("contactMechTypeId", CONTACT_MECH_TYPE_ID));
        GenericValue queryResult = EntityQuery.use(delegator).from("PartyAndContactMech").where(queryConditions).cache(true).queryFirst();
        String contactMechId = "";
        if (queryResult !=null) {
            contactMechId = (String) queryResult.get("contactMechId");
        }
        Debug.log("Retrieve contactMechId: " + contactMechId);

        queryConditions.clear();
        queryConditions.add(EntityCondition.makeCondition("partyId", ownerPartyId));
        queryConditions.add(EntityCondition.makeCondition("roleTypeId", EasyPosPartyWorker.EMPLOYEE_ROLE_TYPE_ID));
        long queryCount = EntityQuery.use(delegator).from("PartyRole").where(queryConditions).cache(true).queryCount();
        if (queryCount > 0) {
            // this is an employee, retrieve owner to get list of stores
            queryConditions.clear();
            queryConditions.add(EntityCondition.makeCondition("partyIdTo", ownerPartyId));
            queryConditions.add(EntityCondition.makeCondition("roleTypeIdFrom", EasyPosPartyWorker.MANAGER_ROLE_TYPE_ID));
            queryConditions.add(EntityCondition.makeCondition("roleTypeIdTo", EasyPosPartyWorker.EMPLOYEE_ROLE_TYPE_ID));
            queryResult = EntityQuery.use(delegator).from("PartyRelationship").where(queryConditions).cache(true).queryFirst();
            if (queryResult != null) {
                ownerPartyId = (String) queryResult.get("partyIdFrom");
            }
        }

        Debug.logInfo("ownerPartyId: " + ownerPartyId, "getAllStoresPerOwner");
        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));

        //-----------------------------------------------------
        // Get all stores per owner
        String sql = FileUtils.readFileToString(new File(GET_STORENAME_PER_OWNER_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, ownerPartyId);
        preparedStatement.setString(2, EasyPosPartyWorker.OWNER_ROLE_TYPE_ID);

        Map<String, Store> storeIdToStoreMap = new HashMap<>();
        List<Store> allStores = new ArrayList<>();
        ResultSet rs = sqlProcessor.executeQuery();
        while (rs.next()) {
            String storeName = rs.getString("store_name");
            String storeId = rs.getString("product_store_id");
            String storeDefaultCurrency = rs.getString("default_currency_uom_id");

            String menuName = rs.getString("menu_name");
            String menuId = rs.getString("menu_id");
            Menu menu = new Menu();
            menu.setName(menuName);
            menu.setId(menuId);

            List<Menu> menus = null;
            if (storeIdToStoreMap.containsKey(storeId)) {
                menus = storeIdToStoreMap.get(storeId).getMenus();
                menus.add(menu);
            } else {
                Store store = new Store();
                store.setName(storeName);
                store.setId(storeId);
                store.setDefaultCurrency(storeDefaultCurrency);

                menus = new ArrayList<>();
                menus.add(menu);
                store.setMenus(menus);

                storeIdToStoreMap.put(storeId, store);
            }
        }

        /*********************************************/

        //-----------------------------------------------------
        // Get all store's customers
        String partyAndPersonAlias = "PAP";
        String productStoreRoleAlias = "PSR";

        DynamicViewEntity dynamicViewEntity = new DynamicViewEntity();

        dynamicViewEntity.addMemberEntity(partyAndPersonAlias, "PartyAndPerson");
        dynamicViewEntity.addMemberEntity(productStoreRoleAlias, "ProductStoreRole");

        dynamicViewEntity.addAlias(productStoreRoleAlias, productStoreRoleAlias + "productStoreId", "productStoreId", null, null, null, null);
        dynamicViewEntity.addAlias(productStoreRoleAlias, productStoreRoleAlias + "roleTypeId", "roleTypeId", null, null, null, null);
        dynamicViewEntity.addAlias(productStoreRoleAlias, productStoreRoleAlias + "partyId", "partyId", null, null, null, null);
        dynamicViewEntity.addAlias(partyAndPersonAlias, partyAndPersonAlias + "firstName", "firstName", null, null, null, null);
        dynamicViewEntity.addAlias(partyAndPersonAlias, partyAndPersonAlias + "lastName", "lastName", null, null, null, null);

        dynamicViewEntity.addViewLink(productStoreRoleAlias, partyAndPersonAlias, Boolean.TRUE, ModelKeyMap.makeKeyMapList("partyId"));

        EntityCondition storeIdCondition = EntityCondition.makeCondition(productStoreRoleAlias + "productStoreId", EntityOperator.IN, storeIdToStoreMap.keySet());
        EntityCondition roleTypeIdCondition = EntityCondition.makeCondition(productStoreRoleAlias + "roleTypeId", CUSTOMER_ROLE_TYPE_ID);
        List<EntityCondition> conditions = new ArrayList<>();
        conditions.add(storeIdCondition);
        conditions.add(roleTypeIdCondition);

        List<GenericValue> allCustomersResult = EntityQuery.use(delegator).from(dynamicViewEntity).where(EntityCondition.makeCondition(conditions)).cache(false).queryList();
        for (GenericValue value : allCustomersResult) {
            String storeId = (String) value.get(productStoreRoleAlias + "productStoreId");
            String customerId = (String) value.get(productStoreRoleAlias + "partyId");
            String customerFirstName = (String) value.get(partyAndPersonAlias + "firstName");
            String customerLastName = (String) value.get(partyAndPersonAlias + "lastName");

            Customer customer = new Customer();
            customer.setId(customerId);
            customer.setName(createCustomerName(customerFirstName, customerLastName));

            Store storeToUpdate = storeIdToStoreMap.get(storeId);
            if (storeToUpdate != null) {
                if (storeToUpdate.getCustomers() != null) {
                    storeToUpdate.getCustomers().add(customer);
                } else {
                    List<Customer> customerList = new ArrayList<>();
                    customerList.add(customer);
                    storeToUpdate.setCustomers(customerList);
                }
            }
        }
        /*********************************************/

        //-----------------------------------------------------
        // Get all store's tables
        EntityCondition tableStoreCondition = EntityCondition.makeCondition("productStoreId", EntityOperator.IN, storeIdToStoreMap.keySet());
        List<GenericValue> allTablesResult = EntityQuery.use(delegator).from("EasyPosProductStoreTable").where(tableStoreCondition).cache(false).queryList();
        for (GenericValue value : allTablesResult) {
            String storeId = (String) value.get("productStoreId");
            String tableName = (String) value.get("tableName");

            Store storeToUpdate = storeIdToStoreMap.get(storeId);
            if (storeToUpdate != null) {
                if (storeToUpdate.getTables() != null) {
                    storeToUpdate.getTables().add(tableName);
                } else {
                    List<String> tables = new ArrayList<>();
                    tables.add(tableName);
                    storeToUpdate.setTables(tables);
                }
            }
        }
        /*********************************************/

        //-----------------------------------------------------
        // Get default facility per owner
        sql = FileUtils.readFileToString(new File(GET_DEFAULT_FACILITY_PER_OWNER_SQL) );
        sqlProcessor.prepareStatement(sql);
        preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, EasyPosPartyWorker.OWNER_ROLE_TYPE_ID);
        preparedStatement.setString(2, ownerPartyId);

        rs = sqlProcessor.executeQuery();
        Facility facility = new Facility();
        if (rs.next()) {
            String name = rs.getString("facility_name");
            String id = rs.getString("facility_id");
            facility.setId(id);
            facility.setName(name);
        }
        List<Facility> facilities = new ArrayList<>();
        facilities.add(facility);
        /*********************************************/

        allStores.addAll(storeIdToStoreMap.values());

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("stores", allStores);
        returnedValues.put("partyId", ownerPartyId);
        returnedValues.put("contactMechId", contactMechId);
        returnedValues.put("defaultFacility", facilities);

        return returnedValues;
    }

    public static String createCustomerName(String firstName, String lastName) {
        String name = lastName.trim().toUpperCase() + " " + firstName.trim().toUpperCase();

        return name.trim();
    }

    public static Map<String, Object> getAllAvailableCurrencies(DispatchContext dctx,
                                                                 Map<String, ? extends Object> context)
            throws GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        List<String> currencies = new ArrayList<>();

        List<GenericValue> queryResult = EntityQuery.use(delegator).from("Uom").where("uomTypeId", "CURRENCY_MEASURE").cache(true).queryList();
        for (GenericValue genericValue : queryResult) {
            currencies.add((String) genericValue.get("uomId"));
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("currencies", currencies);

        return returnedValues;
    }

    private static class Store {
        private String name;
        private String id;
        private String defaultCurrency;
        private List<Menu> menus;
        private List<Customer> customers;
        private List<String> tables;

        public List<String> getTables() {
            return tables;
        }

        public void setTables(List<String> tables) {
            this.tables = tables;
        }

        public List<Customer> getCustomers() {
            return customers;
        }

        public void setCustomers(List<Customer> customers) {
            this.customers = customers;
        }

        public String getDefaultCurrency() {
            return defaultCurrency;
        }

        public void setDefaultCurrency(String defaultCurrency) {
            this.defaultCurrency = defaultCurrency;
        }

        public List<Menu> getMenus() {
            return menus;
        }

        public void setMenus(List<Menu> menus) {
            this.menus = menus;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    private static class Menu {
        private String name;
        private String id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    public static class Facility {
        private String name;
        private String id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }

    private static class Customer {
        private String name;
        private String id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
