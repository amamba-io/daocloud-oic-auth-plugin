# Jenkins插件开发指南

强烈建议先看看为什么需要编写这份插件：[打通jenkins和DCE5系统认证](https://daocloud.feishu.cn/wiki/WGQkwNXoeiMossk9ssoceedanWA)

## 1. 项目概述

这是一个Jenkins OpenID Connect认证插件，该插件实现了OIDC认证流程，为Jenkins提供了现代化的身份验证机制。是基于[oic-auth-plugin](https://github.com/jenkinsci/oic-auth-plugin)插件进行的二次开发。除了OIDC认证外，实现了调用DCE5的鉴权接口
可以实现应用工作台请求jenkins时，直接使用dce5的JWT进行鉴权。


## 2. 开发环境搭建

### 2.1 环境要求
- **Java 17+**
- **Maven 3.9.6+**

## 3. 核心代码结构

### 3.1 主要类和组件

- OicSecurityRealm (src/main/java/org/jenkinsci/plugins/oic/OicSecurityRealm.java)
  核心代码实现，OIDC认证和DCE5鉴权Filter的注册。
-  OicServerConfiguration (src/main/java/org/jenkinsci/plugins/oic/OicServerConfiguration.java)
  配置类，实现UI和CASC的配置管理。
-  AuthorizationFilter (src/main/java/org/jenkinsci/plugins/oic/AuthorizationFilter.java)
  授权过滤器，控制访问权限
- OicSecurityRealm/config.jelly: 主要配置界面（前端UI）使用Jelly模板生成配置表单
  - jelly 文件直接与类相关联。可以调用类的属性和方法，有点类似于Vue.
  - CASC 是如何实现的？
    - jenkins利用JAVA的反射机制，分析字段名和对应的Setxxxx方法，再由yaml映射到java对象，因此字段名不能随便写，需要保持驼峰形式。@DataBoundSetter
    - 因此注意：方法名，参数名，类的属性名不能随便写，因为jenkins会利用反射的机制，以及UI，CASC的配置绑定。
- Messages.properties： 国际化支持文件，包含插件的多语言文本

更多详细的可以看代码注释

### 3.2 扩展点机制

#### 3.3.1 Jenkins扩展点概念
- **Extension**: 使用`@Extension`注解标记扩展点
- **Descriptor**: 配置描述符
- **Stapler**: jenkins 的 web框架，用于处理HTTP请求和响应，代码中的doXXX方法就是Stapler会调用到的方法，不可随意更改

Jenkins插件并不复杂，其中的关键点在于找到系统相关的API作为切入点. 实现一个扩展点，首先需要选择一个合适的扩展点类型，然后创建一个Java类并实现相应的接口或继承相应的类.

#### 3.3.2 常用扩展点类型
- **SecurityRealm**: 安全域扩展
- **Filter**: 过滤器扩展： 对于每个HTTP请求执行，类似于middleware
- **UserProperty**: 用户属性扩展

## 4. 配置说明

以admin用户登录后，需要修改jenkins的认证/授权配置，这部分如何配置可以参考文档[打通jenkins和DCE5系统认证](https://daocloud.feishu.cn/wiki/WGQkwNXoeiMossk9ssoceedanWA)

注意需要将CASC中的部分参数修改为下面的配置：
```yaml
jenkins:
  authorizationStrategy:   
    globalMatrix:
      entries:
      - group:
          name: "authenticated"
          permissions:
          - "Job/Build"
          - "Job/Cancel"
          - "Job/Configure"
          - "Job/Create"
          - "Job/Delete"
          - "Job/Discover"
          - "Job/Move"
          - "Job/Read"
          - "Job/Workspace"
          - "Overall/Read"
          - "Run/Delete"
          - "Run/Update"
      - user:
          name: "admin"
          permissions:
          - "Overall/Administer"
      - user:
          name: "anonymous"
          permissions:
          - "Overall/Read"
  securityRealm:
    oic:
      allowedTokenExpirationClockSkewSeconds: 0
      clientId: "jenkins"
      clientSecret: "{AQAAABAAAAAwu9SOyBp73OODMx3Pn8bjfhKrhAWFPd9wSGeAdzE9F5iYKw/TMQgz4CmLMLwFyZZd2MUq8xu22JGLjbTIZ7sZUA==}"
      disableSslVerification: false
      enableExternalAuth: true              # 是否启用外部鉴权
      escapeHatchEnabled: true
      escapeHatchSecret: "Admin01"
      escapeHatchUsername: "admin"
      externalAuthServiceUrl: "http://172.30.40.54:31785/api/unsafe.amamba.io/v1alpha1/verify-permission"  # 外部鉴权接口地址
      groupIdStrategy: "caseSensitive"
      logoutFromOpenidProvider: false
      serverConfiguration:
        wellKnown:
          wellKnownOpenIDConfigurationUrl: "http://172.30.40.51:30080/auth/realms/ghippo/.well-known/openid-configuration"
      userIdStrategy: "caseSensitive"
      userNameField: "preferred_username"
  unclassified:
    location:
      url: "http://172.30.40.34:30928/"
```

说明：
- authorizationStrategy表示授权策略， 不再采用role based strategy。 采用的是 安全矩阵 的方式。 这里给认证过的用户（通过OIDC登录后的用户肯定是认证用户）给了read和pipeline相关的权限
  - read权限是因为oidc回调需要
  - pipeline相关权限是为了让某个folder下的pipeline相关接口需要走DCE5的鉴权接口
- securityRealm 表示采用的OIDC登录，而不是jenkins内置的用户系统。 如何获取这些配置可以查看飞书文档
  - externalAuthServiceUrl 这个地址目前是amamba提供出来的接口，因为ghippo并没有提供鉴权接口（仅有SDK），因此amamba warp了一下
- unclassified.location.url 表示jenkins的访问地址，注意这里需要和实际的访问地址保持一致，否则会导致OIDC登录回调失败。

上面的casc参数也可以在页面上进行配置，只不过每次配置后jenkins重启都需要重新配置比较繁琐。

> ！！！注意！！！：目前authorizationStrategy这个值在jenkins的CASC配置是有问题的，每次重启jenkins后还是选择的role based strategy，需要进入到jenkins的CASC页面手动reload一下才能生效。可能是CASC插件的bug或者其他原因。

## 5. 鉴权原理说明

- 当配置中开启了使用外部系统进行鉴权（CASC或者UI 中选择了 enableExternalAuth并且填写了externalAuthServiceUrl）,在jenkins插件加载时，会注册一个`AuthorizationFilter`过滤器。这个过滤器会在每次HTTP请求时被调用。
- 在这个过滤器中，会检查请求是否需要进行鉴权（根据httpMethod, PATH, header）来判断
- 如果需要鉴权，则根据请求拼接出鉴权相关的参数：ws, permission，向鉴权接口发起请求
- 根据鉴权接口的返回结果判断是否需要拒绝或者进入下一个过滤器

因为所有的请求都会经过这个Filter，因此可以实现对所有请求的鉴权控制，包括：
- jenkins UI上的请求（这也是为什么需要将authenticated用户的权限设置为很大的原因）
- API请求
  - API请求又包含几类，他们的请求方式都是在Header上有Authorization的请求（目前都做了兼容）
    - DCE 发起的请求
    - 第三方可以使用jenkins token发起的请求
- blueocean 请求也可经过鉴权
  
具体可看代码实现。

## 6. 开发流程

### 调试技巧

1. 需要通过helm 部署一个jenkins

jenkins的JAVA_OPTS中添加以下参数：
```bash
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
````
同时Deployment， Service也需要暴露出这个debug端口

2. 打包/安装插件一条龙
```bash
./deploy.sh 
```
这个脚本中实现了：
- 插件打包
- 调用jenkins的api安装插件
- 重启jenkins
- 打开浏览器访问jenkins

3. 远程调试

-在IDE中设置：Run → Edit Configurations → Add New Configuration → Remote JVM Debug
- 端口: 5005 (默认)
- JVM参数: `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005`

配置完成后启动，就可以像本地debug一样打断点了。


## 7. TODO
- [ ] 解决CASC配置authorizationStrategy不生效的问题
- [ ] 测试更多场景
- [ ] 插件的打包，发布pipeline

## 8. 相关资料

- https://www.jenkins.io/zh/doc/developer/tutorial/prepare/
- https://www.jenkins.io/zh/doc/developer/plugin-development/build-process/
- https://wiki.jenkins-ci.org/display/JENKINS/Plugin+Cookbook
- https://github.com/jenkinsci/google-login-plugin
- https://wiki.jenkins-ci.org/display/JENKINS/Basic+guide+to+Jelly+usage+in+Jenkins
- https://www.jenkins.io/zh/doc/developer/plugin-development/dependencies-and-class-loading/
- https://www.echo.cool/docs/devops/jenkins/jenkins-plugin-development/jenkins-plugin-development-environment/
- https://www.jenkins.io/doc/developer/extensions/
