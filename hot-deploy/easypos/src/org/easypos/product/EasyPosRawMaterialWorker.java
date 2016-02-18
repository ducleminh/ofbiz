package org.easypos.product;


import com.google.common.collect.Lists;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericDelegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityQuery;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class EasyPosRawMaterialWorker {

    private static String[] AVAILABLE_UOMS = {"WT_g","WT_kg","OTH_box","OTH_ea","EN_Kw","VLIQ_M3"};

    public static Map<String, Object> getAvailableUom(DispatchContext dctx,
                                                                    Map<String, ? extends Object> context) throws GenericEntityException {
        GenericDelegator delegator = (GenericDelegator) DelegatorFactory.getDelegator("default");

        List<Uom> uomList = new ArrayList<>();

        EntityCondition condition = EntityCondition.makeCondition("uomId", EntityOperator.IN, Arrays.asList(AVAILABLE_UOMS));
        List<GenericValue> queryResult = EntityQuery.use(delegator).from("Uom").where(condition).cache(true).queryList();
        for (GenericValue row : queryResult) {
            String id = (String) row.get("uomId");
            String type = (String) row.get("uomTypeId");
            String abbr = (String) row.get("abbreviation");
            Uom uom = new Uom();
            uom.setId(id);
            uom.setType(type);
            uom.setAbbr(abbr);

            uomList.add(uom);
        }

        Map<String, Object> returnedValues = ServiceUtil.returnSuccess();
        returnedValues.put("uomList", uomList);

        return returnedValues;
    }

    public static class Uom {
        private String id;
        private String type;
        private String abbr;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getAbbr() {
            return abbr;
        }

        public void setAbbr(String abbr) {
            this.abbr = abbr;
        }
    }
}
