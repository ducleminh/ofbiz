select p.internal_name as product_name,
      p.product_id,
      pp.product_price_type_id,
      pp.price,
      pp.currency_uom_id,
      pa.attr_value as tag_name,
      uom.UOM_ID as uomId,
      uom.UOM_TYPE_ID as uomTypeId,
      uom.ABBREVIATION as abbreviation
from product_role pr
inner join user_login ul on pr.party_id = ul.party_id
inner join product p on pr.product_id = p.product_id
inner join product_price pp on p.product_id = pp.product_id
left join uom uom on pp.TERM_UOM_ID = uom.UOM_ID
left join product_attribute pa on pa.product_id = p.product_id
where ul.user_login_id = ?
and pr.role_type_id = ?
and pp.PRODUCT_PRICE_TYPE_ID = ?
and pp.PRODUCT_PRICE_PURPOSE_ID = ?
and (pa.attr_type is null or pa.attr_type = ?)
and p.PRODUCT_TYPE_ID = ?