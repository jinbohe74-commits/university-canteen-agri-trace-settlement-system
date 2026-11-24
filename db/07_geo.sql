-- ==========================================================================
-- 配送地理坐标（2026-11-10）
-- --------------------------------------------------------------------------
-- 动态地图需要起终点经纬度。原来 farmer/canteen 只有文字地址，画不出位置。
-- 坐标以第一食堂为锚点、按各农户真实的 distance_km 与方位角反推，
-- 保证「坐标算出来的距离」与「里程字段」自洽，不会出现地图上很近却说 15 公里。
-- 可重复执行：加列前先查 information_schema，UPDATE 语句天然幂等。
-- ==========================================================================

-- 幂等加列：MySQL 8 没有 ADD COLUMN IF NOT EXISTS，用 information_schema 判断后动态执行
SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE farmer ADD COLUMN lng DECIMAL(10,6) NULL COMMENT ''基地经度''', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'farmer' AND column_name = 'lng');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE farmer ADD COLUMN lat DECIMAL(10,6) NULL COMMENT ''基地纬度''', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'farmer' AND column_name = 'lat');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE canteen ADD COLUMN lng DECIMAL(10,6) NULL COMMENT ''食堂经度''', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'canteen' AND column_name = 'lng');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

SET @ddl = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE canteen ADD COLUMN lat DECIMAL(10,6) NULL COMMENT ''食堂纬度''', 'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'canteen' AND column_name = 'lat');
PREPARE st FROM @ddl; EXECUTE st; DEALLOCATE PREPARE st;

UPDATE farmer SET lng=117.253819, lat=29.234856 WHERE id=1;
UPDATE farmer SET lng=117.043652, lat=29.299815 WHERE id=2;
UPDATE farmer SET lng=117.378345, lat=29.408590 WHERE id=3;
UPDATE farmer SET lng=117.187204, lat=29.234772 WHERE id=4;
UPDATE farmer SET lng=117.098210, lat=29.424827 WHERE id=5;
UPDATE farmer SET lng=117.317702, lat=29.259932 WHERE id=6;
UPDATE farmer SET lng=117.097164, lat=29.242327 WHERE id=7;
UPDATE farmer SET lng=117.286593, lat=29.499556 WHERE id=8;
UPDATE farmer SET lng=117.276818, lat=29.121449 WHERE id=9;
UPDATE farmer SET lng=117.113800, lat=29.322369 WHERE id=10;
UPDATE farmer SET lng=117.355697, lat=29.331134 WHERE id=11;
UPDATE farmer SET lng=117.109945, lat=29.155494 WHERE id=12;
UPDATE farmer SET lng=117.158087, lat=29.446382 WHERE id=13;
UPDATE farmer SET lng=117.358545, lat=29.189546 WHERE id=14;
UPDATE farmer SET lng=116.993123, lat=29.264856 WHERE id=15;
UPDATE farmer SET lng=117.337803, lat=29.432655 WHERE id=16;
UPDATE farmer SET lng=117.207373, lat=29.089970 WHERE id=17;
UPDATE farmer SET lng=117.030431, lat=29.434885 WHERE id=18;
UPDATE farmer SET lng=117.445640, lat=29.277996 WHERE id=19;
UPDATE farmer SET lng=117.158344, lat=29.259142 WHERE id=20;
UPDATE farmer SET lng=117.206270, lat=29.341571 WHERE id=21;
UPDATE farmer SET lng=117.239291, lat=29.236231 WHERE id=22;
UPDATE farmer SET lng=117.117381, lat=29.303825 WHERE id=23;
UPDATE farmer SET lng=117.278665, lat=29.328305 WHERE id=24;
UPDATE farmer SET lng=117.166595, lat=29.202175 WHERE id=25;
UPDATE farmer SET lng=117.150355, lat=29.377966 WHERE id=26;
UPDATE farmer SET lng=117.312338, lat=29.246253 WHERE id=27;
UPDATE farmer SET lng=117.071637, lat=29.248374 WHERE id=28;
UPDATE farmer SET lng=117.265293, lat=29.399639 WHERE id=29;
UPDATE farmer SET lng=117.237474, lat=29.157324 WHERE id=30;
UPDATE farmer SET lng=117.058998, lat=29.365974 WHERE id=31;
UPDATE farmer SET lng=117.371067, lat=29.313921 WHERE id=32;
UPDATE canteen SET lng=117.198000, lat=29.288000 WHERE id=1;
UPDATE canteen SET lng=117.201000, lat=29.285500 WHERE id=2;
UPDATE canteen SET lng=117.228000, lat=29.276000 WHERE id=3;
UPDATE canteen SET lng=117.195500, lat=29.290500 WHERE id=4;
UPDATE canteen SET lng=117.231000, lat=29.278200 WHERE id=5;
SELECT f.id, f.name, f.distance_km, f.lng, f.lat FROM farmer f ORDER BY f.id LIMIT 6;
