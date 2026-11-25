-- Oracle Autonomous Database (plannerdb) schema for the Facebook Post Planner.
-- Run as ADMIN in Database Actions > SQL Developer Web.
CREATE TABLE posts (
  id VARCHAR2(36) PRIMARY KEY,
  type VARCHAR2(20) NOT NULL,
  caption CLOB DEFAULT '',
  hashtags VARCHAR2(4000),
  scheduled_at TIMESTAMP,
  status VARCHAR2(20) NOT NULL,
  failure_reason VARCHAR2(4000),
  facebook_post_id VARCHAR2(100),
  page_id VARCHAR2(50),
  media_files VARCHAR2(4000) DEFAULT '[]',
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE pages (
  id VARCHAR2(50) PRIMARY KEY,
  name VARCHAR2(500) NOT NULL,
  access_token VARCHAR2(4000) NOT NULL,
  picture_url VARCHAR2(4000),
  long_lived NUMBER(1) DEFAULT 0,
  connected_at TIMESTAMP
);

CREATE TABLE settings (
  key VARCHAR2(100) PRIMARY KEY,
  value VARCHAR2(1000) NOT NULL
);
