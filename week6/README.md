# 极客时间运维进阶训练营第六周作业



## 作业要求

1. 基于logstash filter功能将nginx默认的访问日志及error log转换为json格式并写入elasticsearch
2. 基于logstash收集json格式的nginx访问日志
3. 基于logstash收集java日志并实现多行合并
4. 基于logstash收集syslog类型日志(以haproxy替代网络设备)
5. logstash收集日志并写入Redis、再通过其它logstash消费至elasticsearch并保持json格式日志的解析
6. 基于docker-compose部署单机版本ELK

扩展：

1. 大型的日志收集案例: filebeat-->logstash-->Redis<--logstash-->elasticsearch
2. 日志写入数据库 
3. 地图显示客户端IP城市
4. logstash收集TCP日志



## 1. 基于logstash filter功能将nginx默认的访问日志及error log转换为json格式并写入elasticsearch

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.14
```

### 安装nginx

```bash
# 172.16.17.14
# 下载源码
cd /usr/local/src
wget https://nginx.org/download/nginx-1.22.1.tar.gz

# 安装编译环境
apt install iproute2 ntpdate tcpdump telnet traceroute nfs-kernel-server nfs-common lrzsz tree openssl libssl-dev libpcre3 libpcre3-dev zlib1g-dev ntpdate tcpdump telnet traceroute  gcc openssh-server lrzsz tree openssl libssl-dev libpcre3 libpcre3-dev zlib1g-dev ntpdate tcpdump telnet traceroute iotop unzip zip make

# 安装nginx
tar xvf nginx-1.22.1.tar.gz
cd nginx-1.22.1
./configure --prefix=/apps/nginx
make
make install

# 配置文件
cd /apps/nginx/conf
vim nginx.conf

server_name localhost www.geniuslab.local;

# 修改首页文件
vim /apps/nginx/html/index.html

<h1>develop web1 20221127</h1>

# 检查配置文件
/apps/nginx/sbin/nginx -t
# 预期输出
nginx: the configuration file /apps/nginx/conf/nginx.conf syntax is ok
nginx: configuration file /apps/nginx/conf/nginx.conf test is successful

# 启动nginx
/apps/nginx/sbin/nginx

# 配置dns解析
172.16.17.14 www.geniuslab.local

# 浏览器访问
http://www.geniuslab.local

# 检查默认日志
tail /apps/nginx/logs/access.log
```

### 创建日志收集文件

```bash
# nginx-to-es.conf
cd /etc/logstash/conf.d
vim nginx-to-es.conf

input {
  file {
    path => "/apps/nginx/logs/access.log"
    type => "nginx-accesslog"
    stat_interval => "1"
    start_position => "beginning"
  }

  file {
    path => "/apps/nginx/logs/error.log"
    type => "nginx-errorlog"
    stat_interval => "1"
    start_position => "beginning"
  }
}

filter {
  if [type] == "nginx-accesslog" {
  grok {
    match => { "message" => ["%{IPORHOST:clientip} - %{DATA:username} \[%{HTTPDATE:request-time}\] \"%{WORD:request-method} %{DATA:request-uri} HTTP/%{NUMBER:http_version}\" %{NUMBER:response_code} %{NUMBER:body_sent_bytes} \"%{DATA:referrer}\" \"%{DATA:useragent}\""] }
    remove_field => "message"
    add_field => { "project" => "magedu"}
  }
  mutate {
    convert => [ "[response_code]", "integer"]
    }
  }
  if [type] == "nginx-errorlog" {
    grok {
      match => { "message" => ["(?<timestamp>%{YEAR}[./]%{MONTHNUM}[./]%{MONTHDAY} %{TIME}) \[%{LOGLEVEL:loglevel}\] %{POSINT:pid}#%{NUMBER:threadid}\: \*%{NUMBER:connectionid} %{GREEDYDATA:message}, client: %{IPV4:clientip}, server: %{GREEDYDATA:server}, request: \"(?:%{WORD:request-method} %{NOTSPACE:request-uri}(?: HTTP/%{NUMBER:httpversion}))\", host: %{GREEDYDATA:domainname}"]}
      remove_field => "message"
    }
  }
}

output {
  if [type] == "nginx-accesslog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-nginx-accesslog-%{+yyyy.MM.dd}"
      user => "admin"
      password => "123456"
  }}

  if [type] == "nginx-errorlog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-nginx-errorlog-%{+yyyy.MM.dd}"
      user => "admin"
      password => "123456"
  }}
}
```

### 运行日志收集

```bash
# 检查语法
/usr/share/logstash/bin/logstash -f /etc/logstash/conf.d/nginx-to-es.conf -t
# 预期输出
Configuration OK
[INFO ] 2022-12-02 10:10:53.457 [LogStash::Runner] runner - Using config.test_and_exit mode. Config Validation Result: OK. Exiting Logstash

# 采用服务方式启动采集
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T10:12:00,447][INFO ][logstash.javapipeline    ][main] Pipeline started {"pipeline.id"=>"main"}
[2022-12-02T10:12:00,502][INFO ][filewatch.observingtail  ][main][c1f2a138efdc32d6d022ebb7ed0e82d44dd9001a8ada5d77afc815bfbc3fda57] START, creating Discoverer, Watch with file and sincedb collections
[2022-12-02T10:12:00,501][INFO ][filewatch.observingtail  ][main][e9bef4567b107d6a1eb5deb61fc334fe8f8211a4ad19f41d619fc5a400cd5625] START, creating Discoverer, Watch with file and sincedb collections
[2022-12-02T10:12:00,512][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}
```

### ES验证

多次刷新网页`http://172.16.17.14/`，点击浏览器Head插件，检查是否已创建索引

![image-20221202181945749](assets/image-20221202181945749.png)



### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为develop-nginx-accesslog，索引模式为develop-nginx-accesslog-*，时间戳字段为@timestamp，点击保存

选择Stack Management-->数据视图-->创建数据视图，名称为develop-nginx-errorlog，索引模式为develop-nginx-errorlog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图develop-nginx-accesslog和develop-nginx-errorlog

![image-20221202182321841](assets/image-20221202182321841.png)



## 2. 基于logstash收集json格式的nginx访问日志

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.14
```

### 自定义nginx日志格式

```bash
vim /apps/nginx/conf/nginx.conf  

log_format access_json '{"@timestamp":"$time_iso8601",'
        '"host":"$server_addr",'
        '"clientip":"$remote_addr",'
        '"size":$body_bytes_sent,'
        '"responsetime":$request_time,'
        '"upstreamtime":"$upstream_response_time",'
        '"upstreamhost":"$upstream_addr",'
        '"http_host":"$host",'
        '"uri":"$uri",'
        '"domain":"$host",'
        '"xff":"$http_x_forwarded_for",'
        '"referer":"$http_referer",'
        '"tcp_xff":"$proxy_protocol_addr",'
        '"http_user_agent":"$http_user_agent",'
        '"status":"$status"}';
access_log /apps/nginx/logs/access.log access_json;

# 检查配置
/apps/nginx/sbin/nginx -t
# 预期输出
nginx: the configuration file /apps/nginx/conf/nginx.conf syntax is ok
nginx: configuration file /apps/nginx/conf/nginx.conf test is successful

# reload
/apps/nginx/sbin/nginx -s reload
```

### 创建日志收集文件

```bash
cd /etc/logstash/conf.d
vim nginx-json-log-to-es.conf

input {
  file {
    path => "/apps/nginx/logs/access.log"
    start_position => "end"
    type => "nginx-json-accesslog"
    stat_interval => "1"
    codec => json
  }
}


output {
  if [type] == "nginx-json-accesslog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "nginx-accesslog-17.14-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
  }}
}
```

### 运行日志收集

```bash
# 采用服务方式启动采集
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T12:44:53,483][INFO ][logstash.javapipeline    ][main] Pipeline started {"pipeline.id"=>"main"}
[2022-12-02T12:44:53,565][INFO ][filewatch.observingtail  ][main][c8da712fb77f0715b544ec144f0700095662523deff30190c6be571b7e200f6e] START, creating Discoverer, Watch with file and sincedb collections
[2022-12-02T12:44:53,571][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}
```

### ES验证

多次刷新网页`http://172.16.17.14/`，点击浏览器Head插件，检查是否已创建索引

![image-20221202205016121](assets/image-20221202205016121.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为nginx-accesslog-17.14，索引模式为nginx-accesslog-17.14-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图nginx-accesslog-17.14

![image-20221202205515915](assets/image-20221202205515915.png)

## 3. 基于logstash收集java日志并实现多行合并

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.11
```

### 简单输入输出测试

```bash
# 172.16.17.11
# 安装logstash
cd /usr/local/src
scp 172.16.8.93:/usr/local/src/logstash-8.5.1-amd64.deb .
dpkg -i logstash-8.5.1-amd64.deb

# 编辑java-log-to-es.conf
cd /etc/logstash/conf.d
vim java-log-to-es.conf

input {
  stdin {
    codec => multiline {
      pattern => "^\[[0-9]{4}\-[0-9]{2}\-[0-9]{2}"
      negate => "true"
      what => "previous"
    }
  }
}

output {
  stdout {}
}

# 运行
/usr/share/logstash/bin/logstash -f /etc/logstash/conf.d/java-log-to-es.conf

# 直接输入
Hello
World
[20991229
[2033-11-23
# 预期输出
Hello
World 
[20991229
[2033-11-23
{
          "tags" => [
        [0] "multiline"
    ],
       "message" => "Hello\nWorld\n[20991229",
         "event" => {
        "original" => "Hello\nWorld\n[20991229"
    },
    "@timestamp" => 2022-12-02T14:40:19.409774225Z,
      "@version" => "1",
          "host" => {
        "hostname" => "es1"
    }
}
```

### 创建java日志配置文件

```bash
cd /etc/logstash/conf.d
vim java-log-to-es.conf

input {
  file {
    path => "/data/eslogs/develop-es-cluster.log"
    type => "eslog"
    stat_interval => "1"
    start_position => "beginning"
    codec => multiline {
      #pattern => "^\["
      pattern => "^\[[0-9]{4}\-[0-9]{2}\-[0-9]{2}"
      negate => "true"
      what => "previous"
    }}
}

output {
  if [type] == "eslog" {
    elasticsearch {
      hosts =>  ["172.16.17.11:9200"]
      index => "%{type}-%{+YYYY.ww}"
      user => "admin"
      password => "123456"
    }}
}

# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T14:47:13,516][INFO ][logstash.javapipeline    ][main] Pipeline started {"pipeline.id"=>"main"}
[2022-12-02T14:47:13,574][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}
[2022-12-02T14:47:13,586][INFO ][filewatch.observingtail  ][main][8613702dfc50fdce4fe7ede7ccedcf7c273cd835a935a3db6aed8b4d3a52b9d9] START, creating Discoverer, Watch with file and sincedb collections
```

### ES验证

点击浏览器Head插件，检查是否已创建索引

![image-20221202224801833](assets/image-20221202224801833.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为eslog，索引模式为eslog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图eslog

![image-20221202224929605](assets/image-20221202224929605.png)



## 4. 基于logstash收集syslog类型日志（以haproxy替代网络设备）

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.14
```

### 安装haproxy

```bash
# 172.16.17.14
cd /usr/local/src

# 安装支持https的源
apt-get -y install apt-transport-https ca-certificates curl software-properties-common

# 更新源并安装haproxy
apt update
apt install haproxy

# 配置监听
# 通过haproxy访问kibana
vim /etc/haproxy/haproxy.cfg

listen kibana
  bind 0.0.0.0:5601
  log global
  mode http
  server 172.16.17.11 172.16.17.11:5601 check inter 2s fall 3 rise 3
listen elasticsearch-9200
  bind 0.0.0.0:9200
  log global
  server 172.16.17.12 172.16.17.12:9200 check inter 2s fall 3 rise 3
  server 172.16.17.13 172.16.17.13:9200 check inter 2s fall 3 rise 3
  
# 启动haproxy
systemctl restart haproxy.service 
  
# 配置syslog
# 发送到logstash服务器的514端口
vim /etc/rsyslog.d/49-haproxy.conf

# Send HAProxy messages to a dedicated logfile
:programname, startswith, "haproxy" {
  #/var/log/haproxy.log
  @@172.16.17.14:514
  stop
}

# 启动rsyslog
systemctl restart rsyslog.service
```

### 创建haproxy日志配置文件

```bash
# 172.16.17.14
cd /etc/logstash/conf.d
vim rsyslog-haproxy-to-es.conf

input{
  syslog {
    host => "0.0.0.0"
    type => "rsyslog-haproxy"
    port => "514"  #监听一个本地的端口
}}

output{
  if [type] == "rsyslog-haproxy" {
    elasticsearch {
      hosts =>  ["172.16.17.11:9200"]
      index => "network-syslog-%{+YYYY.ww}"
      user => "admin"
      password => "123456"
    }}
}


# 运行
systemctl restart logstash
ss -tnl

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T14:54:23,742][INFO ][logstash.inputs.syslog   ][main][f7087fea023fe7fe2213dfaae21434441275982519ae908a44f0797938a92353] Starting syslog tcp listener {:address=>"0.0.0.0:514"}
[2022-12-02T14:54:23,783][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}
```

### 验证haproxy

访问`172.16.17.14:5601`，即通过haproxy访问到kibana

### 验证ES

点击浏览器Head插件，检查是否已创建network-syslog-2022.47索引

![image-20221202230645512](assets/image-20221202230645512.png)

### 建立数据视图

访问`http://172.16.17.14:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为network-syslog，索引模式为network-syslog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图network-syslog

![image-20221202230808288](assets/image-20221202230808288.png)



## 5. logstash收集日志并写入Redis、再通过其它logstash消费至elasticsearch并保持json格式日志的解析

### 节点

```bash
# logstash+nginx
172.16.17.14

# redis做缓存
172.16.17.2

# logtash
172.16.17.1

# Elasticsearch
172.16.17.11
```

### 安装redis

```bash
# 172.16.17.2
# 安装redis
apt install redis -y

# 修改配置文件
vim /etc/redis/redis.conf

bind 0.0.0.0
requirepass 123456

# 重启服务
systemctl restart redis-server.service

# 检查6379端口
ss -tbnl

# 测试
telnet 172.16.17.2 6379

auth 123456
# 预期输出
+OK

keys *
# 预期输出
*0

quit
```

### 配置logstash+nginx到redis

```bash
# 172.16.17.14
cd /etc/logstash/conf.d
vim nginx-log-to-redis.conf

input {
  file {
    path => "/var/log/nginx/access.log"
    type => "develop-nginx-accesslog"
    start_position => "beginning"
    stat_interval => "1"
    codec => "json" #对json格式日志进行json解析
  }

  file {
    path => "/apps/nginx/logs/error.log"
    type => "develop-nginx-errorlog"
    start_position => "beginning"
    stat_interval => "1"
  }
}

filter {
  if [type] == "develop-nginx-errorlog" {
    grok {
      match => { "message" => ["(?<timestamp>%{YEAR}[./]%{MONTHNUM}[./]%{MONTHDAY} %{TIME}) \[%{LOGLEVEL:loglevel}\] %{POSINT:pid}#%{NUMBER:threadid}\: \*%{NUMBER:connectionid} %{GREEDYDATA:message}, client: %{IPV4:clientip}, server: %{GREEDYDATA:server}, request: \"(?:%{WORD:request-method} %{NOTSPACE:request-uri}(?: HTTP/%{NUMBER:httpversion}))\", host: %{GREEDYDATA:domainname}"]}
      remove_field => "message" #删除源日志
    }
  }
}


output {
  if [type] == "develop-nginx-accesslog" {
    redis {
      data_type => "list"
      key => "develop-nginx-accesslog"
      host => "172.16.17.2"
      port => "6379"
      db => "0"
      password => "123456"
    }
  }
  if [type] == "develop-nginx-errorlog" {
    redis {
      data_type => "list"
      key => "develop-nginx-errorlog"
      host => "172.16.17.2"
      port => "6379"
      db => "0"
      password => "123456"
    }
  }
}


# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T15:15:41,348][INFO ][filewatch.observingtail  ][main][c56ef0c72d04be09485faf2acd8ec421cc8ea92eada0bcf5edc0de264dd4953f] START, creating Discoverer, Watch with file and sincedb collections
[2022-12-02T15:15:41,389][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}

# 浏览访问nginx产生日志
http://172.16.17.14

# 检查redis写入
telnet 172.16.17.2 6379

auth 123456
+OK
keys *
# 预期输出
*2
$23
develop-nginx-accesslog
$22
develop-nginx-errorlog

# 弹出数据
LPOP develop-nginx-accesslog
# 预期输出
$1017
{"event":{"original":"{\"@timestamp\":\"2022-12-02T15:16:07+00:00\",\"host\":\"172.16.17.14\",\"clientip\":\"172.16.8.102\",\"size\":0,\"responsetime\":0.000,\"upstreamtime\":\"-\",\"upstreamhost\":\"-\",\"http_host\":\"172.16.17.14\",\"uri\":\"/index.html\",\"domain\":\"172.16.17.14\",\"xff\":\"-\",\"referer\":\"-\",\"tcp_xff\":\"-\",\"http_user_agent\":\"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36\",\"status\":\"304\"}"},"upstreamtime":"-","referer":"-","@version":"1","log":{"file":{"path":"/var/log/nginx/access.log"}},"type":"develop-nginx-accesslog","http_host":"172.16.17.14","upstreamhost":"-","host":"172.16.17.14","uri":"/index.html","responsetime":0.0,"size":0,"xff":"-","domain":"172.16.17.14","@timestamp":"2022-12-02T15:16:07.000Z","http_user_agent":"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36","tcp_xff":"-","clientip":"172.16.8.102","status":"304"}

quit
```

### 配置redis到logstash

```bash
# 172.16.17.1
# 安装logstash
cd /usr/local/src
scp 172.16.8.93:/usr/local/src/logstash-8.5.1-amd64.deb .
dpkg -i logstash-8.5.1-amd64.deb

# 配置文件
cd /etc/logstash/conf.d
vim redis-to-es.conf

input {
  redis {
    data_type => "list"
    key => "develop-nginx-accesslog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
    }
  redis {
    data_type => "list"
    key => "develop-nginx-errorlog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
    }
}

output {
  if [type] == "develop-nginx-accesslog" {
    elasticsearch {
       hosts => ["172.16.17.11:9200"]
       index => "redis-develop-nginx-accesslog-%{+yyyy.MM.dd}"
       user => "admin"
       password => "123456"
  }}
  
  if [type] == "develop-nginx-errorlog" {
    elasticsearch {
       hosts => ["172.16.17.11:9200"]
       index => "redis-develop-nginx-errorlog-%{+yyyy.MM.dd}"
       user => "admin"
       password => "123456"
  }}
}


# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T15:45:26,471][INFO ][logstash.inputs.redis    ][main] Registering Redis {:identity=>"redis://<password>@172.16.17.2:6379/0 list:develop-nginx-accesslog"}
[2022-12-02T15:45:26,488][INFO ][logstash.javapipeline    ][main] Pipeline started {"pipeline.id"=>"main"}
[2022-12-02T15:45:26,640][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}

# 浏览访问nginx产生日志
http://172.16.17.14
```

### 验证es索引

点击浏览器Head插件，检查是否已创建索引

![image-20221202234840197](assets/image-20221202234840197.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为redis-develop-nginx-accesslog，索引模式为redis-develop-nginx-accesslog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图redis-develop-nginx-accesslog

同理添加redis-develop-nginx-errorlog视图

![image-20221202235023195](assets/image-20221202235023195.png)



## 6. 基于docker-compose部署单机版本ELK

### 节点

```bash
172.16.17.4
```

### 调整系统参数

```bash
vim /etc/sysctl.conf

vm.max_map_count = 262144

# 运行生效
# 如果出现es容器启动报内存错误，需运行此命令
sysctl -p
```

### 安装logstash

```bash
git clone https://gitee.com/jiege-gitee/elk-docker-compose.git 

cd elk-docker-compose

# 启动elasticsearch容器
docker-compose up -d elasticsearch

# 给内置账号设置密码
docker exec -it elasticsearch /usr/share/elasticsearch/bin/elasticsearch-setup-passwords interactive
# 预期输出
# 设置密码均为123456
Please confirm that you would like to continue [y/N]y
Enter password for [elastic]: 
Reenter password for [elastic]: 
Enter password for [apm_system]: 
Reenter password for [apm_system]: 
Enter password for [kibana_system]: 
Reenter password for [kibana_system]: 
Enter password for [logstash_system]: 
Reenter password for [logstash_system]: 
Enter password for [beats_system]: 
Reenter password for [beats_system]: 
Enter password for [remote_monitoring_user]: 
Reenter password for [remote_monitoring_user]: 

# 修改kibana连接elasticsearch的账户密码：
vim kibana/config/kibana.yml

elasticsearch.username: "kibana_system"
elasticsearch.password: "123456"

# 修改logstash连接elasticsearch的账户密码
vim logstash/config/logstash.yml

xpack.monitoring.elasticsearch.username: logstash_system
xpack.monitoring.elasticsearch.password: "123456"

# 修改Logstash输入输出规则
vim logstash/config/logstash.conf

input {
  tcp {
    port => 9889
    type => "develop-tcplog"
    mode => "server"  
  }
}

output {
  if [type] == "develop-tcplog" {
    elasticsearch {
      hosts => ["elasticsearch:9200"]
      index => "%{type}-%{+YYYY.MM.dd}"
      user => "elastic"
      password => "123456"
  }}
}


# 启动
docker-compose  up -d
```

### 客户端生成数据

```bash
 echo "ERROR tcplog message1" > /dev/tcp/172.16.17.4/9889
```

### 验证es索引

点击浏览器Head插件，添加访问地址为`172.16.17.4:9200`，登录账号为elastic，检查是否已创建develop-tcplog-xxx索引

![image-20221203002043579](assets/image-20221203002043579.png)

### 建立数据视图

访问`http://172.16.17.4:5601`，登录账号为elastic，选择创建索引模式，名称为develop-tcplog-*，时间戳字段为@timestamp，点击创建

选择Analytics-->discover，查看数据视图develop-tcplog

![image-20221203002330047](assets/image-20221203002330047.png)





## 扩展1. 大型的日志收集案例: filebeat-->logstash-->Redis<--logstash-->elasticsearch

### 节点

```bash
# filebeat+nginx
172.16.17.4

# logstash
172.16.17.1

# redis做缓存
172.16.17.2

# logstash
172.16.17.15

# Elasticsearch
172.16.17.11
```

### 配置filebeat+nginx-->logstash-->redis

```bash
# 172.16.17.4
# 安装nginx
cd /usr/local/src
wget https://nginx.org/download/nginx-1.22.1.tar.gz

tar xvf nginx-1.22.1.tar.gz
cd nginx-1.22.1
./configure --prefix=/apps/nginx
make
make install

# nginx日志格式及路径配置
vim /apps/nginx/conf/nginx.conf  

log_format access_json '{"@timestamp":"$time_iso8601",'
        '"host":"$server_addr",'
        '"clientip":"$remote_addr",'
        '"size":$body_bytes_sent,'
        '"responsetime":$request_time,'
        '"upstreamtime":"$upstream_response_time",'
        '"upstreamhost":"$upstream_addr",'
        '"http_host":"$host",'
        '"uri":"$uri",'
        '"domain":"$host",'
        '"xff":"$http_x_forwarded_for",'
        '"referer":"$http_referer",'
        '"tcp_xff":"$proxy_protocol_addr",'
        '"http_user_agent":"$http_user_agent",'
        '"status":"$status"}';
access_log /apps/nginx/logs/access.log access_json;

# 启动nginx
/apps/nginx/sbin/nginx

# 浏览器访问
http://172.16.17.4
tail /apps/nginx/logs/access.log

# 安装filebeat
cd /usr/local/src
scp 172.16.8.93:/usr/local/src/filebeat-7.12.1-amd64.deb .
dpkg -i filebeat-7.12.1-amd64.deb

# 配置filebeat
vim /etc/filebeat/filebeat.yml

- type: filestream
  id: develop-app1-accesslog-id
  enabled: true
  paths:
    - /apps/nginx/logs/access.log
  fields:
    project: develop
    type: develop-app1-accesslog
- type: filestream
  id: develop-app1-errorlog-id
  enabled: true
  paths:
    - /apps/nginx/logs/error.log
  fields:
    project: develop
    type: develop-app1-errorlog
filebeat.config.modules:
  path: ${path.config}/modules.d/*.yml
  reload.enabled: false
setup.template.settings:
  index.number_of_shards: 1
setup.kibana:
output.logstash:
  enabled: true
  hosts: ["172.16.17.1:5044"]
  loadbalance: true
  worker: 1
  compression_level: 3
processors:
  - add_host_metadata:
      when.not.contains.tags: forwarded
  - add_cloud_metadata: ~
  - add_docker_metadata: ~
  - add_kubernetes_metadata: ~
  
# 手动启动测试
/usr/share/filebeat/bin/filebeat -c /etc/filebeat/filebeat.yml

# 启动filebeat
systemctl restart filebeat
systemctl status filebeat
```

### 配置logstash到redis

```bash
# 172.16.17.1
cd /etc/logstash/conf.d
vim beats-develop-to-redis.conf

# 侦听于5044端口
input {
  beats {
    port => 5044
    codec => "json"
  }
}


output {
  if [fields][type] == "develop-app1-accesslog" {
  redis {
    host => "172.16.17.2"
    password => "123456"
    port => "6379"
    db => "0"
    key => "develop-app1-accesslog"
    data_type => "list"
   }
  }
  if [fields][type] == "develop-app1-errorlog" {
  redis {
    host => "172.16.17.2"
    password => "123456"
    port => "6379"
    db => "0"
    key => "develop-app1-errorlog"
    data_type => "list"
     }
  } 
}

# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log

# 检查redis
telnet 172.16.17.2 6379

auth 123456
+OK
keys *
# 预期输出
*2
$22
develop-app1-accesslog
$21
develop-app1-errorlog
```

### 配置redis到es

```bash
# 172.16.17.15
cd /etc/logstash/conf.d
vim develop-redis-to-es.conf

input {
  redis {
    data_type => "list"
    key => "develop-app1-accesslog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
    codec => "json"  #json解析
  }

  redis {
    data_type => "list"
    key => "develop-app1-errorlog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
  }
}

output {
  if [fields][type] == "develop-app1-accesslog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-app1-accesslog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
  }

  if [fields][type] == "develop-app1-errorlog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-app1-errorlog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
  }
}


# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log

# 浏览访问nginx产生日志
http://172.16.17.4
```

### 验证es索引

点击浏览器Head插件，检查是否已创建索引

![image-20221203204306020](assets/image-20221203204306020.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为develop-app1-accesslog，索引模式为develop-app1-accesslog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图develop-app1-accesslog

![image-20221203204453949](assets/image-20221203204453949.png)



## 扩展2. 日志写入数据库 

### 节点

```bash
# mysql
172.16.17.4

# logstash
172.16.17.15
```

### 安装mysql

```bash
# 172.16.17.4
# 安装
apt install mariadb-server

# 修改配置文件
vim /etc/mysql/mariadb.conf.d/50-server.cnf

bind-address	= 0.0.0.0

# 启动服务
systemctl restart mariadb.service
systemctl enable mariadb.service

# 创建数据库
mysql

create database elk character set utf8 collate utf8_bin;
grant all privileges on elk.* to elk@"%" identified by '123456';
flush privileges;

# 远程测试连接
apt install mysql-client
mysql -uelk -p123456 -h172.16.17.4

show databases;
```

### 日志输出到数据库

```bash
# 172.16.17.15
# 安装logstash-output-jdbc插件
/usr/share/logstash/bin/logstash-plugin install logstash-output-jdbc
# 查找是否已安装
/usr/share/logstash/bin/logstash-plugin list | grep jdbc
# 预期输出
logstash-integration-jdbc
 ├── logstash-input-jdbc
 ├── logstash-filter-jdbc_streaming
 └── logstash-filter-jdbc_static
logstash-output-jdbc

# logstash配置mysql-connector-java
wget https://dev.mysql.com/get/Downloads/Connector-J/mysql-connector-j_8.0.31-1ubuntu22.04_all.deb

dpkg -i /usr/local/src/mysql-connector-j_8.0.31-1ubuntu22.04_all.deb
mkdir -pv /usr/share/logstash/vendor/jar/jdbc
cp /usr/share/java/mysql-connector-j-8.0.31.jar  /usr/share/logstash/vendor/jar/jdbc/
chown logstash.logstash /usr/share/logstash/vendor/jar/ -R

# 启动
systemctl restart logstash.service
```

### 创建数据库表

```bash
# 字段time的默认值设置为CURRENT_TIMESTAMP
use elk;
drop table elklog;
create table elklog (
  clientip varchar(512),
  size int(32),
  responsetime float,
  uri varchar(512),
  http_user_agent varchar(512),
  status int(32),
  time timestamp default current_timestamp()
) engine=InnoDB default charset=utf8 collate=utf8_unicode_ci;
```

### 配置logstash

```bash
# 172.16.17.15
cd /etc/logstash/conf.d
vim develop-filebeat-redis-to-mysql-es.conf

input {
  redis {
    data_type => "list"
    key => "develop-app1-accesslog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
    codec => "json"  #json解析
  }

  redis {
    data_type => "list"
    key => "develop-app1-errorlog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
  }
}

output {
  if [fields][type] == "develop-app1-accesslog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-app1-accesslog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
    jdbc {
   connection_string => "jdbc:mysql://172.16.17.4/elk?user=elk&password=123456&useUnicode=true&characterEncoding=UTF8"
   statement => ["INSERT INTO elklog(clientip,size,responsetime,uri,http_user_agent,status) VALUES(?,?,?,?,?,?)","clientip","size","responsetime","uri","http_user_agent","status"]
      }
    }

  if [fields][type] == "develop-app1-errorlog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-app1-errorlog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
  }
}

# 重启服务
systemctl restart logstash.service
```

### 数据库验证

多次刷新网页`http://172.16.17.4/`

客户端连接数据库，检查elklog表是否有数据

![image-20221204073807962](assets/image-20221204073807962.png)

### ES验证

点击浏览器Head插件，检查是否已创建索引

![image-20221204073842800](assets/image-20221204073842800.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为develop-app1-accesslog，索引模式为develop-app1-accesslog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图develop-app1-accesslog

![image-20221204073917731](assets/image-20221204073917731.png)



## 扩展3. 地图显示客户端IP城市

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.10

# nginx
172.16.17.4
```

### 安装logstash

```bash
# 172.16.17.10
# 卸载原版本
dpkg -l logstash*
dpkg -P logstash

# 安装7.12.1版本
scp 172.16.8.93:/usr/local/src/logstash-7.12.1-amd64.deb .
dpkg -i logstash-7.12.1-amd64.deb
```

### 上传地图数据库

```bash
# 172.16.17.10
# 上传GeoLite2-City_20221122.tar.gz
scp 172.16.8.93:/usr/local/src/GeoLite2-City_20221122.tar.gz .
mv GeoLite2-City_20221122.tar.gz /etc/logstash/
tar zxvf GeoLite2-City_20221122.tar.gz
```

### 创建日志配置文件

```bash
# 172.16.17.10
cd /etc/logstash/conf.d
vim develop-filebeat-redis-to-mysql-es.conf

input {
  redis {
    data_type => "list"
    key => "develop-app1-accesslog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
    codec => "json"  #json解析
  }

  redis {
    data_type => "list"
    key => "develop-app1-errorlog"
    host => "172.16.17.2"
    port => "6379"
    db => "0"
    password => "123456"
  }
}

filter {
  if [fields][type] == "develop-app1-accesslog"   {
    geoip {
      source => "clientip"
      target => "geoip"
      database => "/etc/logstash/GeoLite2-City_20221122/GeoLite2-City.mmdb"
      add_field => [ "[geoip][coordinates]", "%{[geoip][longitude]}" ]
      add_field => [ "[geoip][coordinates]", "%{[geoip][latitude]}"  ]
    }
    mutate {
    convert => [ "[geoip][coordinates]", "float"]
   }
 }
}

# 索引名必须以logstash-开头
output {
  if [fields][type] == "develop-app1-accesslog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "logstash-develop-app1-accesslog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
  }
   
  if [fields][type] == "develop-app1-errorlog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "logstash-develop-app1-errorlog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
    }
  }
}


# 重启logstash
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
```

### 导入公网访问日志

```bash
# 172.16.17.4
# 上传blog-nginx_access.log.zip
scp 172.16.8.93:/usr/local/src/blog-nginx_access.log.zip .

# 解压
unzip blog-nginx_access.log.zip 
cat blog-nginx_access.log >> /apps/nginx/logs/access.log
```

### ES验证

点击浏览器Head插件，检查是否已创建索引

![image-20221204091521009](assets/image-20221204091521009.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为logstash-develop-app1-accesslog，索引模式为logstash-develop-app1-accesslog-*，时间戳字段为@timestamp，点击保存

选择visualize library-->新建可视化-->Maps-->添加图层-->文档，选择数据视图logstash-develop-app1-accesslog，出现地理空间字段geoip.location，在日历上选择今日，添加图层-->保存并关闭

![image-20221204092606571](assets/image-20221204092606571.png)



## 扩展4. logstash收集TCP日志

### 节点

```bash
# Elasticsearch
172.16.17.11

# logstash
172.16.17.14
```

### 创建tcp日志配置文件

```bash
# 172.16.17.14
cd /etc/logstash/conf.d
vim tcp-log-to-es.conf

input {
  tcp {
    port => 9889
    type => "develop-tcplog"
    mode => "server"  
  }
}


output {
  if [type] == "develop-tcplog" {
    elasticsearch {
      hosts => ["172.16.17.11:9200"]
      index => "develop-tcplog-%{+YYYY.MM.dd}"
      user => "admin"
      password => "123456"
  }}
}

# 运行
systemctl restart logstash

# 检查logstash启动情况
tail -f /var/log/logstash/logstash-plain.log
# 预期输出
[2022-12-02T14:47:13,516][INFO ][logstash.javapipeline    ][main] Pipeline started {"pipeline.id"=>"main"}
[2022-12-02T14:47:13,574][INFO ][logstash.agent           ] Pipelines running {:count=>1, :running_pipelines=>[:main], :non_running_pipelines=>[]}
[2022-12-02T14:47:13,586][INFO ][filewatch.observingtail  ][main][8613702dfc50fdce4fe7ede7ccedcf7c273cd835a935a3db6aed8b4d3a52b9d9] START, creating Discoverer, Watch with file and sincedb collections
```

### 向logstash发送日志

```bash
echo "ERROR tcplog message1"  > /dev/tcp/172.16.17.14/9889

# 通过nc
apt install netcat
echo "nc test" | nc 172.16.17.14 9889
nc 172.16.17.14 9889 < /etc/passwd
```

### ES验证

点击浏览器Head插件，检查是否已创建索引

![image-20221203002043579](assets/image-20221203002043579.png)

### 建立数据视图

访问`http://172.16.17.11:5601`，选择Stack Management-->数据视图-->创建数据视图，名称为develop-tcplog，索引模式为develop-tcplog-*，时间戳字段为@timestamp，点击保存

选择Analytics-->discover，查看数据视图develop-tcplog

![image-20221203002330047](assets/image-20221203002330047.png)
