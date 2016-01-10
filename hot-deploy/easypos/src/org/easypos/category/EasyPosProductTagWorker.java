package org.easypos.category;

import org.apache.commons.io.FileUtils;
import org.easypos.catalog.EasyPosCatalogWorker;
import org.easypos.party.EasyPosPartyWorker;
import org.easypos.product.EasyPosProductWorker;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.jdbc.SQLProcessor;
import org.ofbiz.service.*;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

public class EasyPosProductTagWorker {

    private static final String GET_PRODUCT_TAGS_PER_OWNER_SQL= "./hot-deploy/easypos/sql/GetAllProductTagsPerOwner.sql";
    public static final String INTERNAL_CATEGORY = "INTERNAL_CATEGORY";
    public static final String DEFAULT_PRODUCT_CATALOG_CATEGORY_TYPE = "PCCT_PURCH_ALLW";

    public static Map<String, Object> findAllProductTagsPerOwner(DispatchContext dctx, Map<String, ? extends Object> context)
            throws IOException, SQLException, GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        GenericValue userLoginGenericValue = (GenericValue) context.get("userLogin");
        String username = (String) userLoginGenericValue.get("userLoginId");

        Set<String> allProductTagsPerOwner = new HashSet<>();
        Map<String, Object> returnedValues;

        SQLProcessor sqlProcessor = new SQLProcessor(delegator, delegator.getGroupHelperInfo("org.ofbiz"));
        String sql = FileUtils.readFileToString(new File(GET_PRODUCT_TAGS_PER_OWNER_SQL) );
        sqlProcessor.prepareStatement(sql);
        PreparedStatement preparedStatement = sqlProcessor.getPreparedStatement();
        preparedStatement.setString(1, EasyPosPartyWorker.OWNER_ROLE_TYPE_ID);
        preparedStatement.setString(2, EasyPosProductWorker.PRODUCT_TAG_ATTR_TYPE);
        preparedStatement.setString(3, username);

        ResultSet rs = sqlProcessor.executeQuery();
        while (rs.next()) {
            String name = rs.getString("tag_name");
            allProductTagsPerOwner.add(name.toUpperCase());
        }

        List<String> returnedTags = new ArrayList<>();
        returnedTags.addAll(allProductTagsPerOwner);

        returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("tags", returnedTags);

        return returnedValues;
    }
}
