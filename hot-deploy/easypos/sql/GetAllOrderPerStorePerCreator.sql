select oh.order_id,
  oh.status_id as order_status_id,
  oh.order_name,
  oh.order_date,
  oi.order_item_seq_id,
  oi.status_id as order_item_status_id,
  oi.product_id,
  oi.quantity,
  p.internal_name as product_name,
  pp.price,
  pp.currency_uom_id
from order_header oh
inner join order_item oi on oh.order_id = oi.order_id
inner join product p on oi.product_id = p.product_id
inner join product_price pp on oi.product_id = pp.product_id
where oh.order_type_id = ?
and oh.product_store_id = ?
and oh.created_by = ?
and oi.status_id <> ?
and oh.status_id in (?,?,?,?)

