package com.wordtrail.ops;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Fail before the web server starts accepting traffic; schema initialization must finish first. */
@Component @DependsOnDatabaseInitialization
public class DatabaseSchemaVerifier implements SmartInitializingSingleton {
    private final DataSource dataSource;

    public DatabaseSchemaVerifier(DataSource dataSource) { this.dataSource=dataSource; }

    @Override public void afterSingletonsInstantiated() {
        var required=new Properties();
        try(var source=new ClassPathResource("schema-contract.properties").getInputStream()) {
            required.load(source);
        } catch(IOException ex) {
            throw new IllegalStateException("无法读取数据库结构检查清单 schema-contract.properties");
        }
        var missing=new ArrayList<String>();
        try(var connection=dataSource.getConnection()) {
            var actual=new HashMap<String,Set<String>>();
            // The current catalog/schema confines metadata to the configured database on MySQL and H2.
            try(var columns=connection.getMetaData().getColumns(connection.getCatalog(),connection.getSchema(),null,null)) {
                while(columns.next()) actual.computeIfAbsent(columns.getString("TABLE_NAME").toLowerCase(Locale.ROOT),k->new HashSet<>())
                    .add(columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
            for(String table:new TreeSet<>(required.stringPropertyNames())) {
                var columns=actual.get(table);
                if(columns==null) { missing.add(table+"（缺表）"); continue; }
                for(String column:required.getProperty(table).split(",")) if(!columns.contains(column)) missing.add(table+"."+column);
            }
        } catch(SQLException ex) {
            // Driver messages may contain connection information; expose only stable diagnostic codes.
            throw new IllegalStateException("数据库结构检查失败，无法核对迁移至 012；SQLState="+ex.getSQLState()+"，错误码="+ex.getErrorCode());
        }
        if(!missing.isEmpty()) throw new IllegalStateException("数据库结构未就绪，缺少："+String.join("、",missing)
            +"。请先备份并检查增量迁移是否已执行至 012；不要重跑已完成的迁移。应用停止启动。");
    }
}
