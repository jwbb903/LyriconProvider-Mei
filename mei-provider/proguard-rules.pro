-dontwarn java.lang.reflect.AnnotatedType
-repackageclasses ''

# Room 生成的 SQL / 表名是字符串常量, 不需要 keep;
# 实体类字段名会出现在 SQL 里, 混淆前后一致, 无需额外规则。
