CREATE DATABASE IF NOT EXISTS aegis_alpha
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'marketmind'@'localhost' IDENTIFIED BY 'marketmind_dev';
GRANT ALL PRIVILEGES ON aegis_alpha.* TO 'marketmind'@'localhost';
FLUSH PRIVILEGES;
