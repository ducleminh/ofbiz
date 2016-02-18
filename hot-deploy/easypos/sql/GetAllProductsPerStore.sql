select p.internal_name as product_name,
      p.product_id,
      pp.product_price_type_id,
      pp.price,
      pp.currency_uom_id,
      pa.attr_value as tag_name,
      uom.UOM_ID as uomId,
      uom.UOM_TYPE_ID as uomTypeId,
      uom.ABBREVIATION as abbreviation
from product_store_catalog psc
inner join prod_catalog_category pcc on psc.prod_catalog_id = pcc.prod_catalog_id
inner join product_category_member pcm on pcc.product_category_id = pcm.product_category_id
inner join product p on pcm.product_id = p.product_id
inner join product_price pp on p.product_id = pp.product_id
left join uom uom on pp.TERM_UOM_ID = uom.UOM_ID
left join product_attribute pa on pa.product_id = p.product_id
where psc.product_store_id = ?
and psc.prod_catalog_id = ?
and pcc.prod_catalog_category_type_id = ?
and pp.product_price_type_id = ?
and pp.product_price_purpose_id = ?
and (pa.attr_type is null or pa.attr_type = ?)