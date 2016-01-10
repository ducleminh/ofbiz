select f.facility_name, f.facility_id
from facility_party fp
inner join facility f on fp.facility_id = f.facility_id
where fp.role_type_id = ?
and fp.party_id = ?