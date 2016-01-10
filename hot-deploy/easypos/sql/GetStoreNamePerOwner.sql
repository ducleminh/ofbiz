select ps.store_name,
      ps.product_store_id,
      ps.default_currency_uom_id,
      pc.catalog_name as menu_name,
      pc.prod_catalog_id as menu_id
from product_store_role psr
inner join user_login ul on ul.party_id = psr.party_id
inner join product_store ps on psr.product_store_id = ps.product_store_id
left join product_store_catalog psc on psc.product_store_id = ps.product_store_id
left join prod_catalog pc on psc.prod_catalog_id = pc.prod_catalog_id
where ul.user_login_id = ?
and psr.role_type_id = ?