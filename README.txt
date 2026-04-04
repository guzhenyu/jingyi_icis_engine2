# 1. 未入库文件说明
src\main\resources\fonts\msyh.ttf
src\main\resources\fonts\SimSun.ttf
src\test\resources\text_resources\fonts\msyh.ttf
src\test\resources\text_resources\fonts\SimSun.ttf

# 2. 部署文件 传输示例

scp .\target\jingyi_icis_engine-0.0.1-SNAPSHOT.jar jingyi@192.168.0.49:/jydata/bin/
scp ./src/main/resources/config/db/schema.postgresql.sql jingyi@192.168.0.49:/jydata/bin/

scp D:\git_code\重症\icis_bridge\target\icis_bridge-0.0.1-SNAPSHOT.jar jingyi@192.168.0.49:/jydata/bin/
scp D:\git_code\重症\icis_bridge\src\main\resources\config\data_bridge_config.pb.txt jingyi@192.168.0.49:/jydata/bin/

scp D:\git_code\重症\icis_j3\target\icis_j3-0.0.1-SNAPSHOT.jar jingyi@192.168.0.49:/jydata/bin/
scp D:\git_code\重症\icis_jd\target\icis_jd-0.0.1-SNAPSHOT.jar jingyi@192.168.0.49:/jydata/bin/
scp D:\git_code\重症\icis_jd\src\main\resources\config\device_driver.txt jingyi@192.168.0.49:/jydata/bin/

# 3. 启动命令示例
# lsof -i :8080
nohup java -jar ./jingyi_icis_engine-0.0.1-SNAPSHOT.jar --cert_pb_txt=./安徽省第二人民医院数字证书.txt --public_key_file_path=./jingyi_pub.key 1>>/jydata/log/output/nohup_engine.txt 2>>/jydata/log/output/nohup_engine.txt &
java -jar ./target/jingyi_icis_engine-0.0.1-SNAPSHOT.jar --cert_pb_txt="C:\Users\gzyrm\Desktop\晶医\02重症项目\安徽省二院\证书\安徽省第二人民医院数字证书.txt" --public_key_file_path="C:\Users\gzyrm\Desktop\晶医\02重症项目\安徽省二院\证书\jingyi_pub.key" --spring.datasource.url=jdbc:postgresql://192.168.0.49:5432/jingyi_icis_db
java -jar ./target/jingyi_icis_engine-0.0.1-SNAPSHOT.jar --jingyi.textresources.icis_config="classpath:/config/pbtxt/icis_config.pb.txt"  --spring.datasource.url=jdbc:postgresql://192.168.0.223:5432/jingyi_icis_db
java -jar ./target/jingyi_icis_engine-0.0.1-SNAPSHOT.jar --jingyi.textresources.icis_config="classpath:/config/pbtxt/hospitals/ah2_icis_config.pb.txt"  --spring.datasource.url=jdbc:postgresql://192.168.0.223:5432/jingyi_icis_db

# lsof -i :8082
nohup java -jar ./icis_bridge-0.0.1-SNAPSHOT.jar 1>>/jydata/log/output/nohup_bridge.txt 2>>/jydata/log/output/nohup_bridge.txt &
nohup java -jar ./icis_bridge-0.0.1-SNAPSHOT.jar --spring.datasource.url=jdbc:postgresql://192.168.0.49:5432/jingyi_icis_db 1>>/jydata/log/output/nohup_bridge.txt 2>>/jydata/log/output/nohup_bridge.txt --server.port=8082 --rpc.port=50003 &

# lsof -i :8083
# 50004
nohup java -jar ./icis_j3-0.0.1-SNAPSHOT.jar --txtdumper.path=/jydata/log/output/j3_msg.txt 1>>/jydata/log/output/nohup_j3.txt 2>>/jydata/log/output/nohup_j3.txt &
nohup java -jar ./icis_j3-0.0.1-SNAPSHOT.jar  --server.port=8083 --rpc.port=50003 --tcp.server.port=50004 --txtdumper.path=/jydata/log/output/j3_msg.txt 1>>/jydata/log/output/nohup_j3.txt 2>>/jydata/log/output/nohup_j3.txt &

# lsof -i :8084
#50005
nohup java -jar ./icis_jd-0.0.1-SNAPSHOT.jar --txtdumper.path=/jydata/log/output/jd_msg.txt 1>>/jydata/log/output/nohup_jd.txt 2>>/jydata/log/output/nohup_jd.txt &
nohup java -jar ./icis_jd-0.0.1-SNAPSHOT.jar --server.port=8084 --rpc.port=50003 --tcp.server.port=50005 --txtdumper.path=/jydata/log/output/jd_msg.txt 1>>/jydata/log/output/nohup_jd.txt 2>>/jydata/log/output/nohup_jd.txt &

# 4. 数据库连接示例
$ psql -h localhost -p 5432 -d jingyi_dev -U jingyi_dev

----------------------------------
目标是为bga配置模块增加两个映射关系:
1. bga_params.lis_result_code 到 patient_lis_results.external_param_code 的映射
2. bga_param_mappings.lis_result_code到patient_lis_results.external_param_code的映射

映射1核心逻辑：
1. 表结构修改：bga_params中增加lis_result_code字段，删除bga_param_mappings表
2. 将icis_web_api.proto:/api/bga/enableparam 接口改成
/*
 * /api/bga/savebgaparam
 * input: SaveBgaParamReq
 * output: GenericResp
 */
message SaveBgaParamReq {
    string dept_id = 1; // 科室ID
    string param_code = 2; // 监测参数代码
    string lis_result_code = 3; // 检验映射代码: 用户可配置任意值，将被用于搜索patient_lis_results表; 也可以为空，从设备取
    bool enabled = 4; // 是否启用
}
3. 前端交互修改：BgaConfig/index.tsx 中每个参数在表格中增加一列'检验结果编码映射'，这一列可以输入（input），失去焦点后如果值发生改变则调用savebgaparam
3.1 在Text.ts中定义 ‘检验结果编码映射' 这类常量

映射2核心逻辑：
1. bga_category_mappings.bga_category_id 的可选值有3个: jingyi_icis_engine2/src/main/resources/config/pbtxt/icis_config.pb.txt:13602-13613
1.1 这3个可选值，前端可以通过接口/api/config/getconfig:GetConfigResp.bga_enums获得
2. 在icis_web_api.proto中增加两个接口
 /api/bga/getbgacategory
 输入 dept_id 输出：repeated BgaCategoryMappingPB (根据配置，目前需要含3个元素）
 /api/bga/savebgacategory
 输入 BgaCategoryMappingPB, 输出GenericResp
2.1 在icis_bga.proto中定义：
message BgaCategoryMappingPB {
  int64 id = 1;
  string dept_id = 2;
  int32 bga_category_id = 3;
  string lis_item_code = 4;
}
并在入口路由&BgaService中实现
3. 前端交互：BgaConfig/index.tsx 中增加一个table，根据getConfigResp.bga_enums的值，渲染一个3列表格:
  第一列 血气类别：根据 bga_category_id getConfigResp.bga_enums.bga_category 显示 名称
  第二列 检验大项编码：可以输入任意字符串, 可以为空
  第三列 操作：如果第二列和查询值不同，则‘保存'按钮从灰化变亮，可保存
3.1 你对比下 参数表 和 类别表 上下排比较好，还是左右排比较好，选美观简洁的

注意点:
1. 补齐单元测试jingyi_icis_engine2/src/test/java/com/jingyicare/jingyi_icis_engine/service/monitorings/BgaServiceTests.java
2. 核心要求：查漏补缺，UI美观


上下文：
1. 整体目录范围：前端根目录 jingyi_icis_frontend/; 后端根目录 jingyi_icis_engine2/; 不涉及其他目录
2. 存储
jingyi_icis_engine2/src/main/resources/config/db/schema.postgresql.sql:3130,3189
src/main/java/com/jingyicare/jingyi_icis_engine/entity/monitorings/*
3. 后端定义的前端交互接口：
jingyi_icis_engine2/src/main/proto/icis_web_api.proto:/api/bga/enableparam
4. 后端接口入口路由：
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/controller/IcisController.java
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/WebApiService.java
5. 后端实现
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/monitorings/BgaService.java
6. 后端配置定义
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/ConfigProtoService.java
jingyi_icis_engine2/src/main/resources/config/pbtxt/icis_config.pb.txt
7. 后端错误码定义
jingyi_icis_engine2/src/main/proto/icis_web_api.proto:StatusCode
jingyi_icis_engine2/src/main/resources/config/pbtxt/icis_config.pb.txt
jingyi_icis_engine2/src/test/resources/text_resources/icis_config.pb.txt
8. 前端页面
jingyi_icis_frontend/src/pages/home/tabs/settings/BgaConfig
9. 前端文本常量
jingyi_icis_frontend/src/api/Text.ts
