---
sidebar_position: 3
---

# Deploying Distributed Cluster

This page provides instructions on how to deploy a *distributed cluster* for Fluss on bare machines.


## Requirements

### Hardware Requirements

Fluss runs on all *UNIX-like environments*, e.g. **Linux**, **Mac OS X**.
To build a distributed cluster, you need to have at least two nodes.
This doc provides a simple example of how to deploy a distributed cluster on three nodes.

### Software Requirements

Before you start to set up the system, make sure you have the following software installed **on each node**:
- **Java 17** or higher (Java 8 and Java 11 are not recommended)
- **Zookeeper 3.6.0** or higher (It is not recommended to use zookeeper versions below 3.6.0)

If your cluster does not fulfill these software requirements you will need to install/upgrade it.

### `JAVA_HOME` Configuration

Flink requires the `JAVA_HOME` environment variable to be set on all nodes and point to the directory of your Java installation.

## Fluss Setup

This part will describe how to set up Fluss cluster consisting of one coordinator server and multiple tablet servers
across three machines. Suppose you have three nodes have ip address:
- Node1: `192.168.10.1`
- Node2: `192.168.10.2`
- Node3: `192.168.10.3`

Node1 will deploy the CoordinatorServer and one TabletServer, Node2 and Node3 will deploy one TabletServer.

### Preparation

1. Make sure ZooKeeper has been deployed, and assuming the ZooKeeper address is `192.168.10.99:2181`. see [Running zookeeper cluster](https://zookeeper.apache.org/doc/r3.6.0/zookeeperStarted.html#sc_RunningReplicatedZooKeeper) to deploy a distributed ZooKeeper.

2. Download Fluss


Go to the [downloads page](/downloads) and download the latest Fluss release. After downloading the latest release, copy the archive to all the nodes and extract it:

```shell
tar -xzf fluss-0.5.0-bin.tgz
cd fluss-0.5.0/
```

### Configuring Fluss

After having extracted the archived files, you need to configure Fluss for the cluster by editing `conf/server.yaml`

For **Node1**, the config is as follows:
```yaml
coordinator.host: 192.168.10.1
coordinator.port: 9123
zookeeper.address: 192.168.10.99:2181
zookeeper.path.root: /fluss

tablet-server.host: 192.168.10.1
tablet-server.id: 1
```

For **Node2**, the config is as follows:
```yaml
zookeeper.address: 192.168.10.99:2181
zookeeper.path.root: /fluss

tablet-server.host: 192.168.10.2
tablet-server.id: 2
```

For **Node3**, the config is as follows:
```yaml
zookeeper.address: 192.168.10.99:2181
zookeeper.path.root: /fluss

tablet-server.host: 192.168.10.3
tablet-server.id: 3
```

:::note
- `tablet-server.id` is the unique id of the TabletServer, if you have multiple TabletServers, you should set different id for each TabletServer.
- In this example, we only set the properties that must be configured, and for some other properties, you can refer to [Configuration](/docs/maintenance/configuration/) for more details.
  :::

### Starting Fluss

To start Fluss, you should first to start a CoordinatorServer in **node1** and
then start each TabletServer in **node1**, **node2**, **node3**. The command is as follows:

#### Starting CoordinatorServer

In **node1**, starting a CoordinatorServer:
```shell
./bin/coordinator-server.sh start
```

#### Starting TabletServer

In **node1**, **node2**, **node3**, starting a TabletServer is as follows:
```shell
./bin/tablet-server.sh start
```

After that, the Fluss distributed cluster is started.

## Interacting with Fluss

After the Fluss cluster is started, you can use **Fluss Client** (e.g., Flink SQL Client) to interact with Fluss.
The following subsections will show you how to use Flink SQL Client to interact with Fluss.

### Flink SQL Client

Using Flink SQL Client to interact with Fluss.

#### Preparation

You can start a Flink standalone cluster refer to [Flink Environment Preparation](/docs/engine-flink/getting-started#preparation-when-using-flink-sql-client)

**Note**: Make sure the [Fluss connector jar](/downloads/) already has copied to the `lib` directory of your Flink home.

#### Add catalog

In Flink SQL client, a catalog is created and named by executing the following query:
```sql title="Flink SQL Client"
CREATE CATALOG fluss_catalog WITH (
  'type'='fluss',
  'bootstrap.servers' = '192.168.10.1:9123'
);
```

#### Do more with Fluss

After the catalog is created, you can use Flink SQL Client to do more with Fluss, for example, create a table, insert data, query data, etc.
More details please refer to [Flink Getting Started](/docs/engine-flink/getting-started/).
