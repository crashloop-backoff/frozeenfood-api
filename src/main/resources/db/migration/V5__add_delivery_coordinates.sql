-- Smart Delivery (Rute Kurir): simpan koordinat tujuan di tabel deliveries.
-- Nullable karena data pengiriman lama belum memiliki koordinat tujuan.
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS latitude_tujuan DOUBLE PRECISION;
ALTER TABLE deliveries ADD COLUMN IF NOT EXISTS longitude_tujuan DOUBLE PRECISION;
