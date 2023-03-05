# 极客时间运维进阶训练营第十五周作业

## 作业要求

1、添加两个以上静态令牌认证的用户，例如tom和jerry，并认证到Kubernetes上；添加两个以上的X509证书认证到Kubernetes的用户，比如mason和magedu；把认证凭据添加到kubeconfig配置文件进行加载；

2、使用资源配置文件创建ServiceAccount，并附加一个imagePullSecrets；

3、为tom用户授予管理blog名称空间的权限；为jerry授予管理整个集群的权限；为mason用户授予读取集群资源的权限；

4、部署Jenkins、Prometheus-Server、Node-Exporter至Kubernetes集群；而后使用Ingress开放至集群外部，Jenkins要使用https协议开放；

5、使用helm部署主从复制的MySQL集群，部署wordpress，并使用ingress暴露到集群外部；使用helm部署harbor，成功验证推送Image至Harbor上；使用helm部署一个redis cluster至Kubernetes上；



## 1. 添加令牌认证

- 添加两个以上静态令牌认证的用户，例如tom和jerry，并认证到Kubernetes上；
- 添加两个以上的X509证书认证到Kubernetes的用户，比如mason和magedu；
- 把认证凭据添加到kubeconfig配置文件进行加载；

### 1.1 添加两个以上静态令牌认证的用户，例如tom和jerry，并认证到Kubernetes上

```bash
# 创建目录
mkdir -p week15/static-token-auth/auth
cd week15/static-token-auth/auth

# 生成tom和jerry的用户token
echo "$(openssl rand -hex 3).$(openssl rand -hex 8)"
715d45.e9cadadbb2a717bc

echo "$(openssl rand -hex 3).$(openssl rand -hex 8)"
a5f241.42f006664aa4efd9

# 创建token文件
vim token.csv

715d45.e9cadadbb2a717bc,tom,1001,kuber-users
a5f241.42f006664aa4efd9,jerry,1002,kuber-admins

# 复制auth目录
cp -rp auth /etc/kubernetes/

# 复制kube-apiserver资源编排文件
cd week15/static-token-auth
cp /etc/kubernetes/manifests/kube-apiserver.yaml .

# 修改kube-apiserver.yaml
vim kube-apiserver.yaml

spec:
  containers:
  - command:
    # 开启token认证
    - --token-auth-file=/etc/kubernetes/auth/token.csv
  volumeMounts: 
    # 挂载token认证的卷
    - mountPath: /etc/kubernetes/auth
      name: users-static-token
      readOnly: true
volumes:
# 定义token认证要挂载的卷
- hostPath:
    path: /etc/kubernetes/auth
    type: DirectoryOrCreate
  name: users-static-token
  
# 复制kube-apiserver.yaml文件
cp kube-apiserver.yaml /etc/kubernetes/manifests/
    
# 查看资源
# 修改kube-apiserver.yaml需等待服务重启完毕
kubectl get pod

# 用token查看
kubectl --server https://kubeapi.igalaxycn.com:6443 --token="715d45.e9cadadbb2a717bc" --certificate-authority=/etc/kubernetes/pki/ca.crt get pods

kubectl --server https://kubeapi.igalaxycn.com:6443 --token="a5f241.42f006664aa4efd9" --certificate-authority=/etc/kubernetes/pki/ca.crt get pods

# 用curl验证
curl -k -H "Authorization: Bearer 715d45.e9cadadbb2a717bc" -k https://kubeapi.igalaxycn.com:6443/api/v1/namespaces/default/pods/

curl -k -H "Authorization: Bearer a5f241.42f006664aa4efd9" -k https://kubeapi.igalaxycn.com:6443/api/v1/namespaces/default/pods/
```



![image-20230303222904764](assets/image-20230303222904764.png)



### 1.2 添加两个以上的X509证书认证到Kubernetes的用户，比如mason和magedu

```bash
# 创建mason和magedu的证书私钥
cd /etc/kubernetes/pki
openssl genrsa -out mason.key 4096
openssl genrsa -out magedu.key 4096

# 用上面的私钥创建csr证书签名请求文件
# CN为用户名，O为用户组
openssl req -new -key mason.key -out mason.csr -subj "/CN=mason/O=developers"
openssl req -new -key magedu.key -out magedu.csr -subj "/CN=magedu/O=operation"

# 通过集群的CA根证书为用户签署，生成crt证书
openssl x509 -req -days 365 -CA /etc/kubernetes/pki/ca.crt -CAkey /etc/kubernetes/pki/ca.key -CAcreateserial -in mason.csr -out mason.crt

openssl x509 -req -days 365 -CA /etc/kubernetes/pki/ca.crt -CAkey /etc/kubernetes/pki/ca.key -CAcreateserial -in magedu.csr -out magedu.crt

# 合并证书
# cat mason.key mason.csr > mason.pem
# cat magedu.key magedu.csr > magedu.pem

# 访问测试
kubectl -s https://kubeapi.igalaxycn.com:6443 --client-certificate=/etc/kubernetes/pki/mason.crt --client-key=/etc/kubernetes/pki/mason.key --certificate-authority=/etc/kubernetes/pki/ca.crt get pods

kubectl -s https://kubeapi.igalaxycn.com:6443 --client-certificate=/etc/kubernetes/pki/magedu.crt --client-key=/etc/kubernetes/pki/magedu.key --certificate-authority=/etc/kubernetes/pki/ca.crt get pods

# 输出
缺少授权信息，无法看到资源
```



![image-20230305134131797](assets/image-20230305134131797.png)



### 1.3 把认证凭据添加到kubeconfig配置文件进行加载

#### 配置集群

```bash
# 设置Cluster
kubectl config set-cluster kube-test \
  --embed-certs=true \
  --certificate-authority=/etc/kubernetes/pki/ca.crt \
  --server=https://kubeapi.igalaxycn.com:6443 \
  --kubeconfig=$HOME/.kube/kubeusers.conf
```

#### 添加静态令牌凭据

```bash
# 配置文件中添加tom和jerry
kubectl config set-credentials tom --token="715d45.e9cadadbb2a717bc"  --kubeconfig=$HOME/.kube/kubeusers.conf

kubectl config set-credentials jerry --token="a5f241.42f006664aa4efd9"  --kubeconfig=$HOME/.kube/kubeusers.conf

# 添加Context配置
kubectl config set-context tom@kube-test --cluster=kube-test --user=tom --kubeconfig=$HOME/.kube/kubeusers.conf

kubectl config set-context jerry@kube-test --cluster=kube-test --user=jerry --kubeconfig=$HOME/.kube/kubeusers.conf

# 验证kubeconfig文件配置
kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=tom@kube-test

kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=jerry@kube-test
```



![image-20230305154735101](assets/image-20230305154735101.png)



#### 添加X509客户端证书

```bash
# 配置文件中添加mason和magedu
kubectl config set-credentials mason \
  --embed-certs=true \
  --client-certificate=/etc/kubernetes/pki/mason.crt \
  --client-key=/etc/kubernetes/pki/mason.key \
  --kubeconfig=$HOME/.kube/kubeusers.conf
  
kubectl config set-credentials magedu \
  --embed-certs=true \
  --client-certificate=/etc/kubernetes/pki/magedu.crt \
  --client-key=/etc/kubernetes/pki/magedu.key \
  --kubeconfig=$HOME/.kube/kubeusers.conf

# 添加Context配置
kubectl config set-context mason@kube-test \
  --cluster=kube-test \
  --user=mason \
  --kubeconfig=$HOME/.kube/kubeusers.conf
  
kubectl config set-context magedu@kube-test \
  --cluster=kube-test \
  --user=magedu \
  --kubeconfig=$HOME/.kube/kubeusers.conf

# 使用mason操作集群
kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=mason@kube-test

kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=magedu@kube-test
```



![image-20230305155742375](assets/image-20230305155742375.png)



## 2. 使用资源配置文件创建ServiceAccount，并附加一个imagePullSecrets

```bash
## 创建namespace
kubectl create namespace test

# 创建docker-registry secret
kubectl create secret docker-registry myreg --docker-username=freecow --docker-password=<password> -n test

# 编辑一个ServiceAccount配置文件
mkdir -p week15/serviceaccount
cd week15/serviceaccount
vim serviceaccount-mysa.yaml

apiVersion: v1
kind: ServiceAccount
metadata:
  name: mysa
  namespace: test

# 创建serviceaccount
kubectl apply -f serviceaccount-mysa.yaml
kubectl get serviceaccounts -n test

# 创建nginx，采用私有镜像
kubectl create deployment nginx --image=freecow/nginx:latest -n test

# 查看pod
kubectl get pod -n test

============>>out
NAME                     READY   STATUS         RESTARTS   AGE
nginx-6c8875c688-vj2zd   0/1     ErrImagePull   0          35s
============>>end

# 查看pod详细信息
kubectl describe pod nginx-6c8875c688-vj2zd -n test

============>>out
...
Error response from daemon: pull access denied for freecow/nginx
============>>end

# 修改serviceaccount加入imagePullSecret
# 引用定义好的secret
vim serviceaccount-mysa.yaml 

apiVersion: v1
kind: ServiceAccount
imagePullSecrets:
- name: myreg
metadata:
  name: mysa
  namespace: test
  
# 重新应用yaml文件
kubectl apply -f serviceaccount-mysa.yaml

# 修改nginx serviceAccountName
kubectl edit deploy nginx -n test

spec:
  containers:
      serviceAccountName: mysa

# 查看pod
kubectl get pod -n test
```

![image-20230304002006692](assets/image-20230304002006692.png)



## 3. 为tom用户授予管理blog名称空间的权限；为jerry授予管理整个集群的权限；为mason用户授予读取集群资源的权限

### 3.1 为tom用户授予管理blog名称空间的权限

```bash
#  创建blog namespace
kubectl create namespace blog

# 创建nginx deployment控制器应用
kubectl create deployment nginx --image=nginx -n blog

# 创建nginx service
kubectl create service clusterip nginx --tcp=80:80 -n blog

# 查看创建的资源
kubectl get pod -n blog
kubectl get svc -n blog

# 在blog命名空间创建角色manager-blog-ns
kubectl create role manager-blog-ns --verb=* --resource=pods,deployments,daemonsets,replicasets,statefulsets,jobs,cronjobs,ingresses,events,configmaps,endpoints,services -n blog

# 为tom用户绑定角色manager-blog-ns
kubectl create rolebinding tom-as-manage-blog-ns --role=manager-blog-ns --user=tom -n blog

# 查看blog下的资源
kubectl get pod,svc -n blog --kubeconfig=$HOME/.kube/kubeusers.conf --context=tom@kube-test

# 查看default及kube-system下的资源，无权限
kubectl get pods --kubeconfig=$HOME/.kube/kubeusers.conf --context=tom@kube-test

kubectl get pods -n kube-system --kubeconfig=$HOME/.kube/kubeusers.conf --context=tom@kube-test

# 可删除blog下的资源
kubectl delete deploy,svc nginx -n blog --kubeconfig=$HOME/.kube/kubeusers.conf --context=tom@kube-test
```



![image-20230304002954012](assets/image-20230304002954012.png)

### 3.2 为jerry授予管理整个集群的权限

```bash
# 为jerry授予管理集群的权限
kubectl create clusterrolebinding jerry-as-cluster-admin --clusterrole=cluster-admin --user=jerry

# 查看pod资源
kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=jerry@kube-test

# 创建namespace blog
kubectl create namespace blog --kubeconfig=$HOME/.kube/kubeusers.conf --context=jerry@kube-test

# 创建deployment应用nginx可以成功创建
kubectl create deploy nginx --image=nginx --kubeconfig=$HOME/.kube/kubeusers.conf --context=jerry@kube-test
```



![image-20230305161157376](assets/image-20230305161157376.png)



### 3.3 为mason用户授予读取集群资源的权限

```bash
# 为mason用户授予读取集群资源的权限
kubectl create clusterrolebinding mason-as-view --clusterrole=view --user=mason

# 查看pod
kubectl get pod --kubeconfig=$HOME/.kube/kubeusers.conf --context=mason@kube-test

# 创建deployment，无权限
kubectl create deployment nginx --image=nginx --kubeconfig=$HOME/.kube/kubeusers.conf --context=mason@kube-test
```



![image-20230305161422630](assets/image-20230305161422630.png)



## 4. 部署Jenkins、Prometheus-Server、Node-Exporter至Kubernetes集群；而后使用Ingress开放至集群外部，Jenkins要使用https协议开放

### 4.1 部署Jenkins至Kubernetes集群

```bash
# 创建工作目录
mkdir -p week15/jenkins
cd week15/jenkins

# 创建01-namespace-jenkins.yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
  
# 创建02-pvc-jenkins.yaml
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-pvc
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 10Gi
  storageClassName: nfs-csi
  
# 创建03-rbac-jenkins.yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins-master
  namespace: jenkins

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: jenkins-master
rules:
  - apiGroups: ["extensions", "apps"]
    resources: ["deployments"]
    verbs: ["create", "delete", "get", "list", "watch", "patch", "update"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["create", "delete", "get", "list", "watch", "patch", "update"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["create","delete","get","list","patch","update","watch"]
  - apiGroups: [""]
    resources: ["pods/exec"]
    verbs: ["create","delete","get","list","patch","update","watch"]
  - apiGroups: [""]
    resources: ["pods/log"]
    verbs: ["get","list","watch"]
  - apiGroups: [""]
    resources: ["secrets"]
    verbs: ["get"]

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: jenkins-master
roleRef:
  kind: ClusterRole
  name: jenkins-master
  apiGroup: rbac.authorization.k8s.io
subjects:
- kind: ServiceAccount
  name: jenkins-master
  namespace: jenkins

# 创建04-deploy-jenkins.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: jenkins
  namespace: jenkins 
spec:
  replicas: 1
  selector:
    matchLabels:
      app: jenkins
  template:
    metadata:
      labels:
        app: jenkins
    spec:
      serviceAccountName: jenkins-master
      volumes:
      - name: jenkins-store
        persistentVolumeClaim:
          claimName: jenkins-pvc
      containers:
      - name: jenkins
        image: jenkins/jenkins:jdk11
        volumeMounts:
        - name: jenkins-store
          mountPath: /var/jenkins_home/
        imagePullPolicy: Always
        env:
        - name: JAVA_OPTS
          value: -XshowSettings:vm -Dhudson.slaves.NodeProvisioner.initialDelay=0 -Dhudson.slaves.NodeProvisioner.MARGIN=50 -Dhudson.slaves.NodeProvisioner.MARGIN0=0.85 -Duser.timezone=Asia/Shanghai -Djenkins.install.runSetupWizard=true
        ports:
        - containerPort: 8080
          name: web
          protocol: TCP
        - containerPort: 50000
          name: agent
          protocol: TCP

# 创建05-service-jenkins.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins
  namespace: jenkins 
  labels:
    app: jenkins
spec:
  selector:
    app: jenkins
  type: NodePort
  ports:
  - name: http
    port: 8080
    targetPort: 8080
---
apiVersion: v1
kind: Service
metadata:
  name: jenkins-jnlp
  namespace: jenkins 
  labels:
    app: jenkins
spec:
  selector:
    app: jenkins
  ports:
  - name: agent
    port: 50000
    targetPort: 50000
    
    
# 创建06-pvc-maven-cache.yaml
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: pvc-maven-cache
  namespace: jenkins
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 10Gi
  storageClassName: nfs-csi


# 启动
kubectl apply -f 01-namespace-jenkins.yaml
kubectl apply -f 02-pvc-jenkins.yaml
kubectl apply -f 03-rbac-jenkins.yaml
kubectl apply -f 04-deploy-jenkins.yaml
kubectl apply -f 05-service-jenkins.yaml
kubectl apply -f 06-pvc-maven-cache.yaml

# 查看创建的资源
kubectl get pod,svc -n jenkins

NAME                           READY   STATUS    RESTARTS   AGE
pod/jenkins-7bdc95fcd8-csnqd   1/1     Running   0          91s

NAME                   TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)          AGE
service/jenkins        NodePort    10.98.191.135   <none>        8080:30898/TCP   86s
service/jenkins-jnlp   ClusterIP   10.103.36.147   <none>        50000/TCP        86s

# 查看jenkins密码
kubectl logs jenkins-7bdc95fcd8-csnqd -n jenkins

============>>out
...
Please use the following password to proceed to installation:

397b7c73216c45359817ebe2761cdcaa
============>>end

# 浏览器访问jenkins
http://172.16.17.21:30898
输入上面的密码
选择安装推荐的插件

# 如果插件安装失败
http://172.16.17.21:30898/pluginManager/advanced
将最下面的Update Site的URL地址替换成：http://mirror.esuni.jp/jenkins/updates/update-center.json，点submit按钮
到Available Plugins中点击check now
然后输入地址 http://172.16.17.21:32312/restart重启 jenkins，再重新安装插件
```



![image-20230305163818762](assets/image-20230305163818762.png)



![image-20230305163932057](assets/image-20230305163932057.png)



### 4.2 部署Prometheus-Server、Node-Exporter至Kubernetes集群

```bash
# 创建工作目录
mkdir -p week15/prom
cd week15/prom

# 创建namespace.yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: prom
  
# 创建prometheus-cfg.yaml
---
kind: ConfigMap
apiVersion: v1
metadata:
  labels:
    app: prometheus
  name: prometheus-config
  namespace: prom
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      scrape_timeout: 10s
      evaluation_interval: 1m

    scrape_configs:
    - job_name: 'kubernetes-apiservers'
      kubernetes_sd_configs:
      - role: endpoints
      scheme: https
      tls_config:
        ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
      relabel_configs:
      - source_labels: [__meta_kubernetes_namespace, __meta_kubernetes_service_name, __meta_kubernetes_endpoint_port_name]
        action: keep
        regex: default;kubernetes;https

    - job_name: 'kubernetes-nodes'
      scheme: https
      tls_config:
        ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
      kubernetes_sd_configs:
      - role: node
      relabel_configs:
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - target_label: __address__
        replacement: kubernetes.default.svc:443
      - source_labels: [__meta_kubernetes_node_name]
        regex: (.+)
        target_label: __metrics_path__
        replacement: /api/v1/nodes/${1}/proxy/metrics

    - job_name: 'kubernetes-cadvisor'
      scheme: https
      tls_config:
        ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      bearer_token_file: /var/run/secrets/kubernetes.io/serviceaccount/token
      kubernetes_sd_configs:
      - role: node
      relabel_configs:
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - target_label: __address__
        replacement: kubernetes.default.svc:443
      - source_labels: [__meta_kubernetes_node_name]
        regex: (.+)
        target_label: __metrics_path__
        replacement: /api/v1/nodes/${1}/proxy/metrics/cadvisor

    - job_name: 'kubernetes-service-endpoints'
      kubernetes_sd_configs:
      - role: endpoints
      relabel_configs:
      - source_labels: [__meta_kubernetes_service_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_service_annotation_prometheus_io_scheme]
        action: replace
        target_label: __scheme__
        regex: (https?)
      - source_labels: [__meta_kubernetes_service_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_service_annotation_prometheus_io_port]
        action: replace
        target_label: __address__
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
      - action: labelmap
        regex: __meta_kubernetes_service_label_(.+)
      - source_labels: [__meta_kubernetes_namespace]
        action: replace
        target_label: kubernetes_namespace
      - source_labels: [__meta_kubernetes_service_name]
        action: replace
        target_label: kubernetes_name

    - job_name: 'kubernetes-pods'
      honor_labels: false
      kubernetes_sd_configs:
      - role: pod
      tls_config:
        insecure_skip_verify: true
      relabel_configs:
      - source_labels: [__meta_kubernetes_namespace]
        action: replace
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        action: replace
        target_label: pod
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scheme]
        action: replace
        target_label: __scheme__
        regex: (.+)

# 创建prometheus-rbac.yaml
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prometheus
rules:
- apiGroups: [""]
  resources:
  - nodes
  - nodes/proxy
  - services
  - endpoints
  - pods
  verbs: ["get", "list", "watch"]
- apiGroups:
  - extensions
  resources:
  - ingresses
  verbs: ["get", "list", "watch"]
- nonResourceURLs: ["/metrics"]
  verbs: ["get"]
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prometheus
  namespace: prom
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prometheus
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: prometheus
subjects:
- kind: ServiceAccount
  name: prometheus
  namespace: prom
  
# 创建prometheus-deploy.yaml
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prometheus-server
  namespace: prom
  labels:
    app: prometheus
spec:
  replicas: 1
  selector:
    matchLabels:
      app: prometheus
      component: server
    #matchExpressions:
    #- {key: app, operator: In, values: [prometheus]}
    #- {key: component, operator: In, values: [server]}
  template:
    metadata:
      labels:
        app: prometheus
        component: server
      annotations:
        prometheus.io/scrape: 'true'
        prometheus.io/port: '9090'
    spec:
      serviceAccountName: prometheus
      containers:
      - name: prometheus
        image: prom/prometheus:v2.40.5
        imagePullPolicy: Always
        command:
          - prometheus
          - --config.file=/etc/prometheus/prometheus.yml
          - --storage.tsdb.path=/prometheus
          - --storage.tsdb.retention=720h
        ports:
        - containerPort: 9090
          protocol: TCP
        resources:
          limits:
            memory: 2Gi
        volumeMounts:
        - mountPath: /etc/prometheus/prometheus.yml
          name: prometheus-config
          subPath: prometheus.yml
        - mountPath: /prometheus/
          name: prometheus-storage-volume
      volumes:
        - name: prometheus-config
          configMap:
            name: prometheus-config
            items:
              - key: prometheus.yml
                path: prometheus.yml
                mode: 0644
        - name: prometheus-storage-volume
          emptyDir: {}
          
# 创建prometheus-svc.yaml
---
apiVersion: v1
kind: Service
metadata:
  name: prometheus
  namespace: prom
  annotations:
    prometheus.io/scrape: 'true'
    prometheus.io/port: '9090'
  labels:
    app: prometheus
spec:
  type: NodePort
  ports:
    - port: 9090
      targetPort: 9090
      nodePort: 30090
      protocol: TCP
  selector:
    app: prometheus
    component: server


# 部署prometheus
kubectl apply -f namespace.yaml 
kubectl apply -f prometheus-cfg.yaml
kubectl apply -f prometheus-rbac.yaml
kubectl apply -f prometheus-deploy.yaml
kubectl apply -f prometheus-svc.yaml

# 查看创建的资源
kubectl get pod,svc -n prom


# 创建node-exporter-ds.yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: prometheus-node-exporter
  namespace: prom
  labels:
    app: prometheus
    component: node-exporter
spec:
  selector:
    matchLabels:
      app: prometheus
      component: node-exporter
  template:
    metadata:
      name: prometheus-node-exporter
      labels:
        app: prometheus
        component: node-exporter
    spec:
      tolerations:
      - effect: NoSchedule
        key: node-role.kubernetes.io/master
      containers:
      - image: prom/node-exporter:v1.5.0
        name: prometheus-node-exporter
        ports:
        - name: prom-node-exp
          containerPort: 9100
          hostPort: 9100
      hostNetwork: true
      hostPID: true
      
# 创建node-exporter-svc.yaml
apiVersion: v1
kind: Service
metadata:
  annotations:
    prometheus.io/scrape: 'true'
  name: prometheus-node-exporter
  namespace: prom
  labels:
    app: prometheus
    component: node-exporter
spec:
  clusterIP: None
  ports:
    - name: prometheus-node-exporter
      port: 9100
      protocol: TCP
  selector:
    app: prometheus
    component: node-exporter
  type: ClusterIP


# 部署node-exporter
kubectl apply -f node-exporter-ds.yaml
kubectl apply -f node-exporter-svc.yaml

# 查看创建的资源
kubectl get pod,svc -n prom

# 浏览器访问prometheus
http://172.16.17.21:30090
```



![image-20230304010511441](assets/image-20230304010511441.png)



### 4.3 使用Ingress开放至集群外部，Jenkins要使用https协议开放

#### 部署ingress

```bash
# https://github.com/kubernetes/ingress-nginx/blob/main/deploy/static/provider/baremetal/deploy.yaml
mkdir -p week15/ingress-nginx
cd week15/ingress-nginx
vim deploy.yaml

apiVersion: v1
kind: Namespace
metadata:
  labels:
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  name: ingress-nginx
---
apiVersion: v1
automountServiceAccountToken: true
kind: ServiceAccount
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx
  namespace: ingress-nginx
---
apiVersion: v1
kind: ServiceAccount
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
  namespace: ingress-nginx
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx
  namespace: ingress-nginx
rules:
- apiGroups:
  - ""
  resources:
  - namespaces
  verbs:
  - get
- apiGroups:
  - ""
  resources:
  - configmaps
  - pods
  - secrets
  - endpoints
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - ""
  resources:
  - services
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - networking.k8s.io
  resources:
  - ingresses
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - networking.k8s.io
  resources:
  - ingresses/status
  verbs:
  - update
- apiGroups:
  - networking.k8s.io
  resources:
  - ingressclasses
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - coordination.k8s.io
  resourceNames:
  - ingress-nginx-leader
  resources:
  - leases
  verbs:
  - get
  - update
- apiGroups:
  - coordination.k8s.io
  resources:
  - leases
  verbs:
  - create
- apiGroups:
  - ""
  resources:
  - events
  verbs:
  - create
  - patch
- apiGroups:
  - discovery.k8s.io
  resources:
  - endpointslices
  verbs:
  - list
  - watch
  - get
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
  namespace: ingress-nginx
rules:
- apiGroups:
  - ""
  resources:
  - secrets
  verbs:
  - get
  - create
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  labels:
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx
rules:
- apiGroups:
  - ""
  resources:
  - configmaps
  - endpoints
  - nodes
  - pods
  - secrets
  - namespaces
  verbs:
  - list
  - watch
- apiGroups:
  - coordination.k8s.io
  resources:
  - leases
  verbs:
  - list
  - watch
- apiGroups:
  - ""
  resources:
  - nodes
  verbs:
  - get
- apiGroups:
  - ""
  resources:
  - services
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - networking.k8s.io
  resources:
  - ingresses
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - ""
  resources:
  - events
  verbs:
  - create
  - patch
- apiGroups:
  - networking.k8s.io
  resources:
  - ingresses/status
  verbs:
  - update
- apiGroups:
  - networking.k8s.io
  resources:
  - ingressclasses
  verbs:
  - get
  - list
  - watch
- apiGroups:
  - discovery.k8s.io
  resources:
  - endpointslices
  verbs:
  - list
  - watch
  - get
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
rules:
- apiGroups:
  - admissionregistration.k8s.io
  resources:
  - validatingwebhookconfigurations
  verbs:
  - get
  - update
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx
  namespace: ingress-nginx
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: ingress-nginx
subjects:
- kind: ServiceAccount
  name: ingress-nginx
  namespace: ingress-nginx
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
  namespace: ingress-nginx
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: ingress-nginx-admission
subjects:
- kind: ServiceAccount
  name: ingress-nginx-admission
  namespace: ingress-nginx
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  labels:
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: ingress-nginx
subjects:
- kind: ServiceAccount
  name: ingress-nginx
  namespace: ingress-nginx
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: ingress-nginx-admission
subjects:
- kind: ServiceAccount
  name: ingress-nginx-admission
  namespace: ingress-nginx
---
apiVersion: v1
data:
  allow-snippet-annotations: "true"
kind: ConfigMap
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-controller
  namespace: ingress-nginx
---
apiVersion: v1
kind: Service
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  ipFamilies:
  - IPv4
  ipFamilyPolicy: SingleStack
  ports:
  - appProtocol: http
    name: http
    port: 80
    protocol: TCP
    targetPort: http
  - appProtocol: https
    name: https
    port: 443
    protocol: TCP
    targetPort: https
  selector:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  type: NodePort
---
apiVersion: v1
kind: Service
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-controller-admission
  namespace: ingress-nginx
spec:
  ports:
  - appProtocol: https
    name: https-webhook
    port: 443
    targetPort: webhook
  selector:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
  type: ClusterIP
---
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-controller
  namespace: ingress-nginx
spec:
  minReadySeconds: 0
  revisionHistoryLimit: 10
  selector:
    matchLabels:
      app.kubernetes.io/component: controller
      app.kubernetes.io/instance: ingress-nginx
      app.kubernetes.io/name: ingress-nginx
  template:
    metadata:
      labels:
        app.kubernetes.io/component: controller
        app.kubernetes.io/instance: ingress-nginx
        app.kubernetes.io/name: ingress-nginx
    spec:
      containers:
      - args:
        - /nginx-ingress-controller
        - --election-id=ingress-nginx-leader
        - --controller-class=k8s.io/ingress-nginx
        - --ingress-class=nginx
        - --configmap=$(POD_NAMESPACE)/ingress-nginx-controller
        - --validating-webhook=:8443
        - --validating-webhook-certificate=/usr/local/certificates/cert
        - --validating-webhook-key=/usr/local/certificates/key
        env:
        - name: POD_NAME
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        - name: LD_PRELOAD
          value: /usr/local/lib/libmimalloc.so
        # image: registry.k8s.io/ingress-nginx/controller:v1.6.4@sha256:15be4666c53052484dd2992efacf2f50ea77a78ae8aa21ccd91af6baaa7ea22f
        image: registry.aliyuncs.com/google_containers/nginx-ingress-controller:v1.6.4
        imagePullPolicy: IfNotPresent
        lifecycle:
          preStop:
            exec:
              command:
              - /wait-shutdown
        livenessProbe:
          failureThreshold: 5
          httpGet:
            path: /healthz
            port: 10254
            scheme: HTTP
          initialDelaySeconds: 10
          periodSeconds: 10
          successThreshold: 1
          timeoutSeconds: 1
        name: controller
        ports:
        - containerPort: 80
          name: http
          protocol: TCP
        - containerPort: 443
          name: https
          protocol: TCP
        - containerPort: 8443
          name: webhook
          protocol: TCP
        readinessProbe:
          failureThreshold: 3
          httpGet:
            path: /healthz
            port: 10254
            scheme: HTTP
          initialDelaySeconds: 10
          periodSeconds: 10
          successThreshold: 1
          timeoutSeconds: 1
        resources:
          requests:
            cpu: 100m
            memory: 90Mi
        securityContext:
          allowPrivilegeEscalation: true
          capabilities:
            add:
            - NET_BIND_SERVICE
            drop:
            - ALL
          runAsUser: 101
        volumeMounts:
        - mountPath: /usr/local/certificates/
          name: webhook-cert
          readOnly: true
      dnsPolicy: ClusterFirst
      nodeSelector:
        kubernetes.io/os: linux
      serviceAccountName: ingress-nginx
      terminationGracePeriodSeconds: 300
      volumes:
      - name: webhook-cert
        secret:
          secretName: ingress-nginx-admission
---
apiVersion: batch/v1
kind: Job
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission-create
  namespace: ingress-nginx
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/component: admission-webhook
        app.kubernetes.io/instance: ingress-nginx
        app.kubernetes.io/name: ingress-nginx
        app.kubernetes.io/part-of: ingress-nginx
        app.kubernetes.io/version: 1.6.4
      name: ingress-nginx-admission-create
    spec:
      containers:
      - args:
        - create
        - --host=ingress-nginx-controller-admission,ingress-nginx-controller-admission.$(POD_NAMESPACE).svc
        - --namespace=$(POD_NAMESPACE)
        - --secret-name=ingress-nginx-admission
        env:
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        image: freecow/kube-webhook-certgen:v20220916-gd32f8c343
        imagePullPolicy: IfNotPresent
        name: create
        securityContext:
          allowPrivilegeEscalation: false
      nodeSelector:
        kubernetes.io/os: linux
      restartPolicy: OnFailure
      securityContext:
        fsGroup: 2000
        runAsNonRoot: true
        runAsUser: 2000
      serviceAccountName: ingress-nginx-admission
---
apiVersion: batch/v1
kind: Job
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission-patch
  namespace: ingress-nginx
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/component: admission-webhook
        app.kubernetes.io/instance: ingress-nginx
        app.kubernetes.io/name: ingress-nginx
        app.kubernetes.io/part-of: ingress-nginx
        app.kubernetes.io/version: 1.6.4
      name: ingress-nginx-admission-patch
    spec:
      containers:
      - args:
        - patch
        - --webhook-name=ingress-nginx-admission
        - --namespace=$(POD_NAMESPACE)
        - --patch-mutating=false
        - --secret-name=ingress-nginx-admission
        - --patch-failure-policy=Fail
        env:
        - name: POD_NAMESPACE
          valueFrom:
            fieldRef:
              fieldPath: metadata.namespace
        image: freecow/kube-webhook-certgen:v20220916-gd32f8c343
        imagePullPolicy: IfNotPresent
        name: patch
        securityContext:
          allowPrivilegeEscalation: false
      nodeSelector:
        kubernetes.io/os: linux
      restartPolicy: OnFailure
      securityContext:
        fsGroup: 2000
        runAsNonRoot: true
        runAsUser: 2000
      serviceAccountName: ingress-nginx-admission
---
apiVersion: networking.k8s.io/v1
kind: IngressClass
metadata:
  labels:
    app.kubernetes.io/component: controller
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: nginx
spec:
  controller: k8s.io/ingress-nginx
---
apiVersion: admissionregistration.k8s.io/v1
kind: ValidatingWebhookConfiguration
metadata:
  labels:
    app.kubernetes.io/component: admission-webhook
    app.kubernetes.io/instance: ingress-nginx
    app.kubernetes.io/name: ingress-nginx
    app.kubernetes.io/part-of: ingress-nginx
    app.kubernetes.io/version: 1.6.4
  name: ingress-nginx-admission
webhooks:
- admissionReviewVersions:
  - v1
  clientConfig:
    service:
      name: ingress-nginx-controller-admission
      namespace: ingress-nginx
      path: /networking/v1/ingresses
  failurePolicy: Fail
  matchPolicy: Equivalent
  name: validate.nginx.ingress.kubernetes.io
  rules:
  - apiGroups:
    - networking.k8s.io
    apiVersions:
    - v1
    operations:
    - CREATE
    - UPDATE
    resources:
    - ingresses
  sideEffects: None


# 部署ingress-controller
kubectl apply -f deploy.yaml 

# 检查
kubectl get pods,svc -n ingress-nginx

# 修改ingress-nginx-controller service配置
kubectl edit svc ingress-nginx-controller -n ingress-nginx

externalTrafficPolicy: Cluster
externalIPs: 
- 172.16.17.100
```

#### 部署prometheus ingress

```bash
# 创建ingress-prometheus.yaml
cd week15/prom
vim ingress-prometheus.yaml

apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: prometheus
  namespace: prom
  labels:
    app: prometheus
spec:
  ingressClassName: 'nginx'
  rules:
  - host: prom.igalaxycn.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: prometheus
            port:
              number: 9090

# 启动
kubectl apply -f ingress-prometheus.yaml

# 查看部署的资源
kubectl get ingress -n prom 

# 修改hosts文件
172.16.17.100 prom.igalaxycn.com

# 浏览器访问prometheus
http://prom.igalaxycn.com
```

![image-20230305205235420](assets/image-20230305205235420.png)



#### 部署jenkins

```bash
# 生成jenkins证书
cd week15/jenkins
mkdir certs
cd certs/
openssl genrsa -out jenkinstls.key 2048

# 签发证书
openssl req -new -x509 -key jenkinstls.key -out jenkinstls.crt -subj "/CN=172.16.17.100"

# 创建jenkins secret
kubectl create secret tls jenkins-ingress-secret --key jenkinstls.key --cert jenkinstls.crt -n jenkins

# 显示secret
kubectl get secret -n jenkins

NAME                     TYPE                DATA   AGE
jenkins-ingress-secret   kubernetes.io/tls   2      6s

# 07-ingress-jenkins.yaml
cd week15/jenkins
vim 07-ingress-jenkins-tls.yaml

apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: jenkins
  namespace: jenkins
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - jenkins.igalaxycn.com
    secretName: jenkins-ingress-secret
  rules:
  - host: jenkins.igalaxycn.com
    http:
      paths:
      - backend:
          service:
            name: jenkins
            port: 
              number: 8080 
        path: /
        pathType: Prefix

# 启动
kubectl apply -f 07-ingress-jenkins-tls.yaml 

# 查看创建的jenkins ingress
kubectl get ingress -n jenkins

NAME      CLASS   HOSTS                   ADDRESS   PORTS     AGE
jenkins   nginx   jenkins.igalaxycn.com             80, 443   7s

# 修改hosts文件
172.16.17.100 prom.igalaxycn.com jenkins.igalaxycn.com

# 浏览访问jenkins
https://jenkins.igalaxycn.com

# 测试完毕后释放资源
kubectl delete -f prom/
kubectl delete -f jenkins/
```



![image-20230305205412306](assets/image-20230305205412306.png)



## 5. 使用helm部署主从复制的MySQL集群，部署wordpress，并使用ingress暴露到集群外部；使用helm部署harbor，成功验证推送Image至Harbor上；使用helm部署一个redis cluster至Kubernetes上

### 5.1 使用helm部署主从复制的MySQL集群，部署wordpress，并使用ingress暴露到集群外部

```bash
# helm添加repo
helm repo add bitnami https://charts.bitnami.com/bitnami

# 显示源
helm repo list

NAME            URL                                    
mysql-operator  https://mysql.github.io/mysql-operator/
bitnami         https://charts.bitnami.com/bitnami 

# 安装主从复制的mysql集群，1主1备
# database.name: wpdb
# root.password: password
# user.password: password
# user.name: wordpress
helm install mysql  \
    --set auth.rootPassword=password \
    --set global.storageClass=nfs-csi \
    --set architecture=replication \
    --set auth.database=wpdb \
    --set auth.username=wordpress \
    --set auth.password=password \
    --set secondary.replicaCount=1 \
    --set auth.replicationPassword=password \
    bitnami/mysql \
    -n blog

     
# 查看创建的资源
kubectl get pod,svc -n blog

NAME                    READY   STATUS    RESTARTS   AGE
pod/mysql-primary-0     0/1     Running   0          113s
pod/mysql-secondary-0   0/1     Running   0          113s

NAME                               TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)    AGE
service/mysql-primary              ClusterIP   10.101.96.68    <none>        3306/TCP   113s
service/mysql-primary-headless     ClusterIP   None            <none>        3306/TCP   113s
service/mysql-secondary            ClusterIP   10.99.171.144   <none>        3306/TCP   113s
service/mysql-secondary-headless   ClusterIP   None            <none>        3306/TCP   113s

# 按照安装提示获取mysql root密码
MYSQL_ROOT_PASSWORD=$(kubectl get secret --namespace blog mysql -o jsonpath="{.data.mysql-root-password}" | base64 -d)

# 启动一个mysql的客户端
kubectl run mysql-client --rm --tty -i --restart='Never' --image  docker.io/bitnami/mysql:8.0.32-debian-11-r8 --namespace blog --env MYSQL_ROOT_PASSWORD=$MYSQL_ROOT_PASSWORD --command -- bash

# 连接实例
mysql -h mysql-primary.blog.svc.cluster.local -uroot -p"$MYSQL_ROOT_PASSWORD"

# 显示已有数据库
mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
| wpdb               |
+--------------------+
5 rows in set (0.01 sec)

# 创建一个test数据库
mysql> create database test;
mysql> exit

# 连接从库，检查是否已同步
mysql -h mysql-secondary.blog.svc.cluster.local -uroot -p"$MYSQL_ROOT_PASSWORD"

mysql> show databases;
+--------------------+
| Database           |
+--------------------+
| information_schema |
| mysql              |
| performance_schema |
| sys                |
| test               |
| wpdb               |
+--------------------+
6 rows in set (0.00 sec)


# 部署wordpress
# 连接mysql主实例
# root.password: password
# user.password: password
# user.name: wordpress
# 访问域名：blog.igalaxycn.com
helm install wordpress \
    --set mariadb.enabled=false \
    --set externalDatabase.host=mysql-primary.blog.svc.cluster.local \
    --set externalDatabase.user=wordpress \
    --set externalDatabase.password=password \
    --set externalDatabase.database=wpdb \
    --set externalDatabase.port=3306 \
    --set persistence.storageClass=nfs-csi \
    --set ingress.enabled=true \
    --set ingress.ingressClassName=nginx \
    --set ingress.hostname=blog.igalaxycn.com \
    --set ingress.pathType=Prefix \
    --set wordpressUsername=admin \
    --set wordpressPassword=password \
    bitnami/wordpress \
    -n blog

# 查看pod
kubectl get pod,svc,pvc,ingress -n blog

NAME                             READY   STATUS    RESTARTS   AGE
pod/mysql-primary-0              1/1     Running   0          19m
pod/mysql-secondary-0            1/1     Running   0          19m
pod/wordpress-66777579f9-7bbp4   1/1     Running   0          3m4s

# 查看service
kubectl get svc -n blog

NAME                               TYPE           CLUSTER-IP      EXTERNAL-IP   PORT(S)                      AGE
service/mysql-primary              ClusterIP      10.101.96.68    <none>        3306/TCP                     19m
service/mysql-primary-headless     ClusterIP      None            <none>        3306/TCP                     19m
service/mysql-secondary            ClusterIP      10.99.171.144   <none>        3306/TCP                     19m
service/mysql-secondary-headless   ClusterIP      None            <none>        3306/TCP                     19m
service/wordpress                  LoadBalancer   10.108.23.40    <pending>     80:32654/TCP,443:30026/TCP   3m4s


# 集群无loadbalancer资源显示pending
# 修改service类型为ClusterIP
kubectl edit svc wordpress -n blog

spec:
  sessionAffinity: None
  type: ClusterIP
  
# 查看service资源
kubectl get svc -n blog

AME                       TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)          AGE
mysql-primary              ClusterIP   10.101.96.68    <none>        3306/TCP         24m
mysql-primary-headless     ClusterIP   None            <none>        3306/TCP         24m
mysql-secondary            ClusterIP   10.99.171.144   <none>        3306/TCP         24m
mysql-secondary-headless   ClusterIP   None            <none>        3306/TCP         24m
wordpress                  ClusterIP   10.108.23.40    <none>        80/TCP,443/TCP   8m14s

# 查看ingress
kubectl get ingress -n blog

NAME        CLASS   HOSTS                ADDRESS        PORTS   AGE
wordpress   nginx   blog.igalaxycn.com   172.16.17.26   80      11m

# 绑定hosts
172.16.17.100 blog.igalaxycn.com

# 浏览器访问测试
http://blog.igalaxycn.com
```



![image-20230305212524299](assets/image-20230305212524299.png)



### 5.2 使用helm部署harbor，成功验证推送Image至Harbor上

```bash
# 添加repo源
helm repo add harbor https://helm.goharbor.io

# 创建工作目录
mkdir -p week15/harbor
cd week15/harbor

# 创建harbor-values.yaml
expose:
  type: ingress
  tls:
    enabled: true  
    certSource: auto
  ingress:
    hosts:
      core: hub.igalaxycn.com
      notary: notary.igalaxycn.com
    controller: default
    annotations: 
      kubernetes.io/ingress.class: "nginx"

ipFamily:
  ipv4:
    enabled: true
  ipv6:
    enabled: false
 

externalURL: https://hub.igalaxycn.com

# 持久化存储配置部分
persistence:
  enabled: true 
  resourcePolicy: "keep"
  persistentVolumeClaim:        # 定义Harbor各个组件的PVC持久卷
    registry:          # registry组件（持久卷）
      storageClass: "nfs-csi"           # 前面创建的StorageClass，其它组件同样配置
      accessMode: ReadWriteMany          # 卷的访问模式，需要修改为ReadWriteMany
      size: 5Gi
    chartmuseum:     # chartmuseum组件（持久卷）
      storageClass: "nfs-csi"
      accessMode: ReadWriteMany
      size: 5Gi
    jobservice:
      jobLog:
        storageClass: "nfs-csi"
        accessMode: ReadWriteOnce
        size: 1Gi
      scanDataExports:
        storageClass: "nfs-csi"
        accessMode: ReadWriteOnce
        size: 1Gi
    database:        # PostgreSQl数据库组件
      storageClass: "nfs-csi"
      accessMode: ReadWriteMany
      size: 2Gi
    redis:    # Redis缓存组件
      storageClass: "nfs-csi"
      accessMode: ReadWriteMany
      size: 2Gi
    trivy:         # Trity漏洞扫描
      storageClass: "nfs-csi"
      accessMode: ReadWriteMany
      size: 5Gi

harborAdminPassword: "password"


# 如果镜像拉取太慢，下载官方离线包并恢复镜像
https://github.com/goharbor/harbor/releases/tag/v2.7.1
tar xf harbor-offline-installer-v2.7.1.tgz
cd harbor
docker load -i harbor.v2.7.1.tar.gz

# 安装harbor
kubectl create namespace harbor
helm install harbor -f harbor-values.yaml harbor/harbor -n harbor

# 查看创建的资源
kubectl get pod,svc,ingress,pvc -n harbor

# 绑定hosts
172.16.17.100 hub.igalaxycn.com

# 浏览访问
https://hub.igalaxycn.com
创建新项目common，访问级别公开

# 客户端推送测试
# 节点172.16.17.30
vim /etc/hosts

172.16.17.100 hub.igalaxycn.com

# 添加harbor地址到insecure-registries
vim /etc/docker/daemon.json
{
  "graph": "/var/lib/docker",
  "storage-driver": "overlay2",
  "insecure-registries": ["hub.igalaxycn.com"],
  "registry-mirrors": ["https://9916w1ow.mirror.aliyuncs.com"],
  "exec-opts": ["native.cgroupdriver=systemd"],
  "live-restore": false,
  "log-opts": {
      "max-file": "5",
      "max-size": "100m"
  }
}

# 重启docker
systemctl restart docker

#登录docker
docker login https://hub.igalaxycn.com -u admin -p password

# 拉取一个nginx镜像
docker pull nginx:latest

# 标记本地镜像，将其归入自建的harbor
docker tag nginx:latest hub.igalaxycn.com/common/nginx:latest

# 推送镜像到harbor
docker push hub.igalaxycn.com/common/nginx:latest

# 浏览访问harbor
https://hub.igalaxycn.com
```



![image-20230306061812364](assets/image-20230306061812364.png)

![image-20230306061842525](assets/image-20230306061842525.png)



![image-20230306061914295](assets/image-20230306061914295.png)



### 5.3 使用helm部署一个redis cluster至Kubernetes上

```bash
# 添加仓库
helm repo add bitnami https://charts.bitnami.com/bitnami

# 搜索redis
helm search repo redis

# 创建工作目录并拉取chart
mkdir -p week15/redis
cd week15/redis
helm pull bitnami/redis-cluster
tar xvf redis-cluster-8.3.10.tgz 

# 修改参数
# 绑定在nfs-csi并设置密码
cd redis-cluster
vim valuse.yaml

global:
  imageRegistry: ""
  ## E.g.
  ## imagePullSecrets:
  ##   - myRegistryKeySecretName
  ##
  imagePullSecrets: []
  storageClass: "nfs-csi"
  redis:
    password: "password"

# 安装redis
kubectl create namespace redis
helm install redis-cluster -f values.yaml bitnami/redis-cluster -n redis

# 查看资源
kubectl get all -n redis

# 根据提示获取密码
export REDIS_PASSWORD=$(kubectl get secret --namespace "redis" redis-cluster -o jsonpath="{.data.redis-password}" | base64 -d)

# 启动客户端
kubectl run --namespace redis redis-cluster-client --rm --tty -i --restart='Never' \
 --env REDIS_PASSWORD=$REDIS_PASSWORD \
--image docker.io/bitnami/redis-cluster:7.0.9-debian-11-r1 -- bash

# 启动redis-cli客户端
redis-cli -c -h redis-cluster -a $REDIS_PASSWORD

# 写入数据
redis-cluster:6379> set jerry 123456
OK
redis-cluster:6379> get jerry
"123456"
```



![image-20230306072214245](assets/image-20230306072214245.png)
