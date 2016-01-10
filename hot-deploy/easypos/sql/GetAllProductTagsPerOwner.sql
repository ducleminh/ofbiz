select distinct pa.attr_value as tag_name
from product_role pr
inner join product_attribute pa on pr.product_id = pa.product_id
inner join user_login ul on pr.party_id = ul.party_id
where pr.role_type_id = ?
and pa.attr_type = ?
and ul.user_login_id = ?