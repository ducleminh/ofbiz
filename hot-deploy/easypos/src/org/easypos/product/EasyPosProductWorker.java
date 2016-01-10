package org.easypos.product;

import org.apache.commons.io.FileUtils;
import org.easypos.category.EasyPosProductTagWorker;
import org.easypos.party.EasyPosPartyWorker;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.*;
import org.ofbiz.entity.jdbc.SQLProcessor;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceContainer;
import org.ofbiz.service.ServiceUtil;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

public class EasyPosProductWorker {

    private static final String DEFAULT_PRODUCT_TYPE_ID = "FINISHED_GOOD";
    private static final String DEFAULT_REQUIRE_INVENTORY = "N";

    public static final String DEFAULT_PRODUCT_PRICE_TYPE = "DEFAULT_PRICE";
    private static final String DEFAULT_PRODUCT_PRICE_PURPOSE = "PURCHASE";
    private static final String DEFAULT_PRICE_WITH_TAX_INCLUDED = "N";
    private static final String DEFAULT_PRODUCT_STORE_GROUP_ID = "_NA_";

    public static final String PRODUCT_TAG_DELIMITER = ",";

    public static final String PRODUCT_TAG_ATTR_TYPE = "TAG";

    private static final String GET_PRODUCTS_PER_OWNER_SQL = "./hot-deploy/easypos/sql/GetAllProductsPerOwner.sql";
    private static final String GET_PRODUCTS_PER_STORE_MENU_SQL = "./hot-deploy/easypos/sql/GetAllProductsPerStore.sql";

    public static Map<String, Object> addNewProduct(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws GenericServiceException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Map<String, Object> serviceResult;
        Map<String, Object> returnedValues;
        String newProductId = null;

        //Get all required params
        String internalProductName = ((String) context.get("internalName")).toUpperCase();
        String currencyUomId = (String) context.get("currencyUomId");
        BigDecimal priceBD = (BigDecimal) context.get("price");
        String productTagDelimitedList = (String) context.get("productTags");

        List<String> productTagsList = Arrays.asList(productTagDelimitedList.split(PRODUCT_TAG_DELIMITER));

        //Create the product
        Map<String, Object> createNewProductParamMap = UtilMisc.toMap(
                "internalName", internalProductName,
                "requireInventory", DEFAULT_REQUIRE_INVENTORY,
                "productTypeId", DEFAULT_PRODUCT_TYPE_ID,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProduct", createNewProductParamMap);
        newProductId = (String) serviceResult.get("productId");

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to create new product: " + internalProductName);
        }
        /*********************************************/

        //Create the product price
        Map<String, Object> createNewProductPriceParamMap = UtilMisc.toMap(
                "productId", newProductId,
                "productPriceTypeId", DEFAULT_PRODUCT_PRICE_TYPE,
                "productPricePurposeId", DEFAULT_PRODUCT_PRICE_PURPOSE,
                "currencyUomId", currencyUomId,
                "productStoreGroupId", DEFAULT_PRODUCT_STORE_GROUP_ID,
                "taxInPrice", DEFAULT_PRICE_WITH_TAX_INCLUDED,
                "price", priceBD,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("createProductPrice", createNewProductPriceParamMap);

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to add price to product: " + internalProductName);
        }
        /*********************************************/

        //Add product's tags
        for (String productTag : productTagsList) {
            Map<String, Object> addProductTagParamMap = UtilMisc.toMap(
                    "productId", newProductId,
                    "attrName", productTag.toUpperCase(),
                    "attrValue", productTag.toUpperCase(),
                    "attrType", PRODUCT_TAG_ATTR_TYPE,
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("createProductAttribute", addProductTagParamMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add product's tag: " + productTag);
            }
        }
        /*********************************************/

        //set owner of the product
        String ownerPartyId = EasyPosPartyWorker.getOwnerPartyId(delegator, username);

        Map<String, Object> addOwnerToProductParamMap = UtilMisc.toMap(
                "productId", newProductId,
                "partyId", ownerPartyId,
                "roleTypeId", EasyPosPartyWorker.OWNER_ROLE_TYPE_ID,
                "userLogin", userLoginGenericValue
        );

        serviceResult = dispatcher.runSync("addPartyToProduct", addOwnerToProductParamMap);

        if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
            return ServiceUtil.returnError("Unable to set product's owner: " + internalProductName);
        }
        /*********************************************/

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("newProductId", newProductId);

        return returnedValues;
    }

    public static Map<String, Object> addMultipleProductsToCategory(DispatchContext dctx, Map<String, ? extends Object> context)
            throws GenericServiceException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");
        LocalDispatcher dispatcher = ServiceContainer.getLocalDispatcher("default", delegator);

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");

        Map<String, Object> paramMap = null;
        Map<String, Object> serviceResult;
        Map<String, Object> returnedValues;

        List<String> productsAddedList = new ArrayList<>();

        String[] productIdList = ((String) context.get("productIdList")).split(EasyPosProductWorker.PRODUCT_TAG_DELIMITER);
        String categoryId = (String) context.get("categoryId");

        //add products to the default category
        for (String productId : productIdList) {
            paramMap = UtilMisc.toMap(
                    "productCategoryId", categoryId,
                    "productId", productId,
                    "fromDate", new Timestamp( (new Date()).getTime()),
                    "userLogin", userLoginGenericValue
            );

            serviceResult = dispatcher.runSync("safeAddProductToCategory", paramMap);
            if (ServiceUtil.isError(serviceResult) || ServiceUtil.isFailure(serviceResult)) {
                return ServiceUtil.returnError("Unable to add product to default category: " + productId);
            }

            productsAddedList.add(productId);
        }
        /*********************************************/

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("productsAdded", productsAddedList);

        return returnedValues;
    }

    public static Map<String, Object> getAllProductsPerOwner(DispatchContext dctx,
                                                    Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Map<String, Object> returnedValues;

        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));
        String sql = FileUtils.readFileToString(new File(GET_PRODUCTS_PER_OWNER_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, username);
        preparedStatement.setString(2, EasyPosPartyWorker.OWNER_ROLE_TYPE_ID);
        preparedStatement.setString(3, DEFAULT_PRODUCT_PRICE_TYPE);
        preparedStatement.setString(4, DEFAULT_PRODUCT_PRICE_PURPOSE);
        preparedStatement.setString(5, PRODUCT_TAG_ATTR_TYPE);

        ResultSet rs = sqlProcessor.executeQuery();
        Collection<Product> productCollection = getProductsFromResultSet(rs);

        List<Product> returnedProducts = new ArrayList<>();
        returnedProducts.addAll(productCollection);

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("products", returnedProducts);

        return returnedValues;
    }

    public static Map<String, Object> getAllProductsPerStoreAndMenu(DispatchContext dctx,
                                                             Map<String, ? extends Object> context)
            throws IOException, GenericEntityException, SQLException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        String storeId = (String) context.get("storeId");
        String menuId = (String) context.get("menuId");

        Map<String, Object> returnedValues;

        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));
        String sql = FileUtils.readFileToString(new File(GET_PRODUCTS_PER_STORE_MENU_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, storeId);
        preparedStatement.setString(2, menuId);
        preparedStatement.setString(3, EasyPosProductTagWorker.DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE);
        preparedStatement.setString(4, DEFAULT_PRODUCT_PRICE_TYPE);
        preparedStatement.setString(5, DEFAULT_PRODUCT_PRICE_PURPOSE);
        preparedStatement.setString(6, PRODUCT_TAG_ATTR_TYPE);

        ResultSet rs = sqlProcessor.executeQuery();
        Collection<Product> productCollection = getProductsFromResultSet(rs);

        List<Product> returnedProducts = new ArrayList<>();
        returnedProducts.addAll(productCollection);

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("products", returnedProducts);

        return returnedValues;
    }

    private static Collection<Product> getProductsFromResultSet(ResultSet rs) throws SQLException {
        Map<String, Product> idToProductMap = new HashMap<>();
        while (rs.next()) {
            String productName = (rs.getString("product_name")).toUpperCase();
            String productId = rs.getString("product_id");
            BigDecimal productPrice = rs.getBigDecimal("price");
            String currency = rs.getString("currency_uom_id");
            String tagName = rs.getString("tag_name");
            if (tagName != null) {
                tagName = tagName.toUpperCase();
            }

            Set<String> tags = null;

            if (idToProductMap.containsKey(productId)) {
                tags = idToProductMap.get(productId).getTags();
                tags.add(tagName);
            } else {
                Product product = new Product();
                product.setName(productName);
                product.setId(productId);
                product.setCurrencySymbol(currency);
                product.setPrice(productPrice.floatValue());

                tags = new HashSet<>();
                tags.add(tagName);
                product.setTags(tags);

                idToProductMap.put(productId, product);
            }
        }

        return idToProductMap.values();
    }

    public static class Product {
        private String name;
        private float price;
        private String currencySymbol;
        private Set<String> tags;
        private String id;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public float getPrice() {
            return price;
        }

        public void setPrice(float price) {
            this.price = price;
        }

        public String getCurrencySymbol() {
            return currencySymbol;
        }

        public void setCurrencySymbol(String currencySymbol) {
            this.currencySymbol = currencySymbol;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Set<String> getTags() {
            return tags;
        }

        public void setTags(Set<String> tags) {
            this.tags = tags;
        }
    }
}
