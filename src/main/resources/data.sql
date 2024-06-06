-- test user
-- userId: reg@email.com
-- password(Bcrypt encrypted): password1234
-- idValue(AES encrypted): 890101-1234567
INSERT INTO "USERS" (user_id, password, name, id_type, id_value, updated_at, created_at) VALUES
 ('reg@email.com', '$2a$10$AmSoXy55b/WDxNkc1wR8GOwPNtDc6GyldVx89J2Sg/cJmtyxum1jK', 'mock', 'REG_NO', 'PsfyWM79htQf+j2kizZF73GLp6xcglPGlGEYvuKPfQazp+48UOll6bR7', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);