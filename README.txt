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
java -jar ./target/jingyi_icis_engine-0.0.1-SNAPSHOT.jar --cert_pb_txt="C:\Users\gzyrm\Desktop\晶医\重症项目\安徽省二院\证书\安徽省第二人民医院数字证书.txt" --public_key_file_path="C:\Users\gzyrm\Desktop\晶医\重症项目\安徽省二院\证书\jingyi_pub.key" --spring.datasource.url=jdbc:postgresql://192.168.0.49:5432/jingyi_icis_db

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
