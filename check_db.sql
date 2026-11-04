SELECT 'User' as table_name, COUNT(*) as count FROM User
UNION ALL
SELECT 'Shop', COUNT(*) FROM Shop
UNION ALL
SELECT 'ReplyTemplate', COUNT(*) FROM ReplyTemplate;
