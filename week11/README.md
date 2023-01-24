# 极客时间运维进阶训练营第十一周作业



## 作业要求

1. 掌握对象存储的特点及使用场景
2. 在两台主机部署radowsgw存储网关以实现高可用环境
3. 基于s3cmd实现bucket的管理及数据的上传和下载
4. 基于Nginx+RGW的动静分离及短视频案例
5. 启用ceph dashboard并基于prometheus监控ceph集群运行状态

扩展：

1. 自定义ceph crush运行图实现基于HDD和SSD磁盘实现数据冷热数据分类存储



## 节点配置

```bash
# osd存储服务器
172.16.17.16
172.16.17.17
172.16.17.18
172.16.17.19

# mon监视服务器
172.16.17.11
172.16.17.12
172.16.17.13

# mgr管理服务器
172.16.17.14
172.16.17.15

# ceph-deploy部署节点
172.16.17.20

# centos-client
172.16.17.21

# ubuntu-client
172.16.17.1
```

## 1. 掌握对象存储的特点及使用场景

### 存储特点

数据不需要放置在目录层次结构中，而是存在于平面地址空间内的同一级别

应用通过唯一地址来识别每个单独的数据对象

通过对象存储网关将数据存储为对象，每个对象除了包含数据，还包含数据自身的元数据。

通过RESTful API在应用级别（而非用户级别）进行访问

对象的存储不是垂直的目录树结构，而是存储在扁平的命名空间中

无论是bucket还是容器，都不能再嵌套（在bucket不能再包含 bucket）

bucket需要被授权才能访问到，一个帐户可以对多个bucket授权，而权限可以不同，有读、写、读写、全部控制权限

### 使用场景


对象存储通过 Object ID 来检索，无法通过普通文件系统的挂载方式通过文件路径加文件名称操作来直接访问对象，只能通过 API 来访问，或者第三方客户端（实际上也是对 API 的封装）

方便横向扩展、快速检索数据

不支持客户端挂载，且需要客户端在访问的时候指定文件名称

不是很适用于文件过于频繁修改及删除的场景



## 2. 在两台主机部署radosGW存储网关以实现高可用环境

### 节点

```bash
# mgr管理服务器
# 高可用的radosGW服务
172.16.17.14
172.16.17.15

# ubuntu-client
172.16.17.1
```

### 安装radosgw

```bash
# ceph-mgr1节点
apt install radosgw

# ceph-mgr2节点
apt install radosgw

# ceph-deploy节点
# 初始化radosGW服务
ceph-deploy rgw create ceph-mgr2
ceph-deploy rgw create ceph-mgr1

# 浏览测试
172.16.17.14:7480
172.16.17.15:7480

# 验证服务状态
ceph -s

# ceph-mgr1和mgr2节点
ps -ef|grep radosgw

# 查看存储池类型
ceph osd pool ls

# 查看存储池信息
radosgw-admin zone get --rgw-zone=default --rgw-zonegroup=default

# 查看副本池规则
ceph osd pool get default.rgw.log crush_rule

# 默认副本数
ceph osd pool get default.rgw.log size

# 默认的pgp数量
ceph osd pool get default.rgw.log pgp_num

# 默认的pg数量
ceph osd pool get default.rgw.log pg_num

# 可自定义http端口，如在ceph-mgr2节点
vim /etc/ceph/ceph.conf

[clientrgw.ceph-mgr2]
rgw_host = ceph-mgr2
rgw_frontends = civetweb port=9900

# 重启服务
systemctl restart ceph-radosgw@rgw.ceph-mgr2.service

# 检查端口
lsof -i:9900

# 浏览测试
172.16.17.15:7480
```

### 安装vip及haproxy

```bash
# ubuntu-client节点
# 安装配置vip
apt install keepalived
cp /usr/share/doc/keepalived/samples/keepalived.conf.vrrp /etc/keepalived/keepalived.conf

# 修改配置文件
vim /etc/keepalived/keepalived.conf

# 创建虚拟地址172.16.17.22，绑定到eth0的子接口0上
virtual_ipaddress {
  172.16.17.22 dev eth0 label eth0:0
}

# 启动服务
systemctl restart keepalived.service

# 测试
ping 172.16.17.22


# 安装haproxy
apt install haproxy

# 修改配置文件
vim /etc/haproxy/haproxy.cfg

# 监听在vip地址的80端口
listen ceph-rgw-7480
  bind 172.16.17.22:80
  mode tcp
  server rgw1 172.16.17.14:7480 check inter 3s fall 3 rise 5
  server rgw2 172.16.17.15:7480 check inter 3s fall 3 rise 5

# 检查配置文件
haproxy -f /etc/haproxy/haproxy.cfg

# 重启服务
systemctl restart haproxy

# 检查是否监听在80端口
ss -tnl

# 浏览主页测试
http://172.16.17.22

# 设置域名指向
172.16.17.22 rgw.igalaxycn.com
```

### rgw节点生成签名证书

```bash
# mgr2节点
# 生成自签名证书
cd /etc/ceph/
mkdir certs
cd certs/
openssl genrsa -out civetweb.key 2048

# 签发证书
openssl req -new -x509 -key civetweb.key -out civetweb.crt -subj "/CN=rgw.igalaxycn.com"

# 把公钥和私钥放到pem文件
cat civetweb.key civetweb.crt > civetweb.pem

# 移动到certs目录
mv civetweb.* /etc/ceph/certs

# 修改为ssl配置
vim /etc/ceph/ceph.conf

# http端口为9900
# https端口为9443
[client.rgw.ceph-mgr1]
rgw_host = ceph-mgr1
rgw_frontends = "civetweb port=9900+9443s ssl_certificate=/etc/ceph/certs/civetweb.pem error_log_file=/var/log/radosgw/civetweb.acccess.log request_timeout_ms=3000 num_threads=200"

[client.rgw.ceph-mgr2]
rgw_host = ceph-mgr2
rgw_frontends = "civetweb port=9900+9443s ssl_certificate=/etc/ceph/certs/civetweb.pem error_log_file=/var/log/radosgw/civetweb.acccess.log request_timeout_ms=3000 num_threads=200"

# 创建日志目录
mkdir /var/log/radosgw

# 查看进程谁在启动
ps -ef|grep rgw

# 修改权限
chown ceph.ceph /var/log/radosgw -R

# 重启服务
systemctl restart ceph-radosgw@rgw.ceph-mgr2.service
ss -tnl
lsof -i:9443

# 访问测试
curl -k https://172.16.17.15:9443

# 验证日志
tail /var/log/radosgw/civetweb.access.log

# ceph-mgr1节点
cd /etc/ceph/certs

# 拷贝证书
scp * 172.16.17.15:/etc/ceph/certs

# 重启服务
systemctl restart ceph-radosgw@rgw.ceph-mgr1.service
ss -tnl
lsof -i:9443

# ubuntu-client节点
# 修改配置文件
vim /etc/haproxy/haproxy.cfg

# 监听在vip地址的80端口
listen ceph-rgw-80
  bind 172.16.17.22:80
  mode tcp
  server rgw1 172.16.17.14:9900 check inter 3s fall 3 rise 5
  server rgw2 172.16.17.15:9900 check inter 3s fall 3 rise 5

# 监听在vip地址的443端口
listen ceph-rgw-443
  bind 172.16.17.22:443
  mode tcp
  server rgw1 172.16.17.14:9443 check inter 3s fall 3 rise 5
  server rgw2 172.16.17.15:9443 check inter 3s fall 3 rise 5

# 检查配置文件
haproxy -f /etc/haproxy/haproxy.cfg

# 重启服务
systemctl restart haproxy

# 浏览主页测试
https://rgw.igalaxycn.com
```



## 3. 基于s3cmd实现bucket的管理及数据的上传和下载

### 修改rgw配置

```bash
# mgr1和mgr2节点
# 修改ceph.conf，去掉上面的pem配置，加上客户端配置
vim /etc/ceph/ceph.conf

[client.rgw.ceph-mgr1]
rgw_host = ceph-mgr1
rgw_frontends = civetweb port=9900
rgw_dns_name = rgw.igalaxycn.com

[client.rgw.ceph-mgr2]
rgw_host = ceph-mgr2
rgw_frontends = civetweb port=9900
rgw_dns_name = rgw.igalaxycn.com

# mgr1节点重启服务
systemctl restart ceph-radosgw@rgw.ceph-mgr1.service

# mgr2节点重启服务
systemctl restart ceph-radosgw@rgw.ceph-mgr2.service
```

### 创建rgw账户

```bash
# ceph-deploy节点
# 创建user1
radosgw-admin user create --uid=”user1” --display-name="user1"

# 保存key
```

### 安装s3cmd

```bash
# ceph-deploy节点
sudo apt-cache madison s3cmd
sudo apt install s3cmd
s3cmd --help

# 配置dns，指向负载均衡
vim /etc/hosts

172.16.17.22 rgw.igalaxycn.com

# 测试
telnet rgw.igalaxycn.com 9900
```

### rbd镜像空间拉伸

```bash

```

### 客户端卸载rbd镜像

```bash
# 客户端卸载
umount /data
rbd --user zhanghui -p rbd-data1 unmap data-img2

# ceph-deploy
# 删除镜像
rbd rm --pool rbd-data1 --image data-img1

# 查看回收站并删除
rbd trash list --pool rbd-data1
rbd trash remove --pool rbd-data1 8659a3691aec

# 列出镜像
rbd ls --pool rbd-data1 -l
```



## 4. 基于Nginx+RGW的动静分离及短视频案例



## 5. 启用ceph dashboard并基于prometheus监控ceph集群运行状态



## 扩展1. 自定义ceph crush运行图实现基于HDD和SSD磁盘实现数据冷热数据分类存储

