# Proof of Concept Guide: Graph-Powered Video Lookup on AWS with Amazon Neptune and Neo4j

**Version:** 1.1  
**Date:** 2026-05-06  
**Audience:** Engineering, Architecture, DevOps, Product Stakeholders

---

## Executive Summary

This document presents the results of a proof of concept for a video search solution built around a simple idea: recorded footage should be searchable in a useful human way. The original motivation came from personal use of a vehicle camera. Like Tesla-style camera systems and other continuous-recording devices, it produced a very large amount of video, but finding a specific moment inside that material was slow and manual.

The purpose of the project was to make video searchable by meaningful situations rather than by file name alone. In the completed proof of concept, the searchable cases included practical traffic and driving events such as a deer appearing in front of the car, a deer crossing the road, an overtaking event, a right turn, a left turn, or a truck appearing in the opposite lane. The value of the solution is that it leads users back to the right video file and scene much faster, based on indexed events and detected context.

The proof of concept showed that a graph database is a strong fit for that problem. Event relationships, movement, temporal order, and co-occurrence were modeled and queried more naturally in a graph-oriented structure than in a simple file-based or flat metadata approach. In this solution, the graph stored searchable indexes and relationships, while the actual video files remained stored separately.

An important part of the result is that the solution can be programmed, loaded, and queried in the Cypher language family. In practice, Amazon Neptune was used through its openCypher-compatible interface, which allowed the proof of concept to demonstrate both write operations and search operations in a Cypher-style model. That means the database can be used not only to store searchable video metadata, but also to search it directly with graph queries that describe business events.

From the perspective of this proof of concept, Amazon Neptune handled the Cypher-style programming model very well for the intended scope. It supported the practical needs of the prototype for storing indexed metadata, linking events, and querying those relationships in a way that was understandable both technically and functionally. For management stakeholders, the key takeaway is that the selected AWS graph platform proved capable of supporting storage and search in the same conceptual language model.

The document also reflects an important business trade-off: graph technology was useful for this use case, but it was also relatively expensive. For that reason, the recommended direction is not to place everything into the graph database, but to use it where it creates the most value, namely for storing searchable metadata, event links, and indexes that lead users back to the correct video files.

At the same time, it is important to record the access context for this phase of the work. At the time of testing, ERSTE Digital was not yet ready to provide an internal AWS setup with company-managed users and authentication for this type of experimental work. Because of that, the current proof of concept was executed in an independent, private sandbox-style AWS access model. For the initial test phase, this was sufficient and appropriate for validation purposes.

The practical access path used for the test phase was a one-time personal AWS allowance in the range of EUR 100, which was adequate to complete the initial validation. In practice, the card used for account verification was only needed to validate access; a virtual bank card was used for that purpose and then discarded after the verification step. From a management point of view, the important message is that the initial phase was completed with limited personal sandbox access and without dependency on a full enterprise AWS onboarding flow.

For future phases, however, continued work should ideally move into a company-provided test environment. Further testing on top of on-demand cloud database services only makes sense once an appropriate corporate environment is available, including proper authentication, ownership, and governance. Based on general AWS onboarding practice, a company can often conduct an initial test period with very limited cost exposure over roughly one to two months, especially in a sandbox-style setup. That said, this assumption should be validated against current AWS policy and against any company-specific account structure, because the exact terms will differ from enterprise-connected environments.

From a business perspective, this work demonstrated that the concept is practical, explainable, and viable in an AWS environment. It also provided a concrete comparison between Neo4j and Amazon Neptune as possible foundations for a searchable video index, while keeping the focus on user value: making large video archives easier to search, review, and reuse.

---

## Original Product Concept and Why This POC Exists

The original idea was to build a **video search engine for events**, not a traditional file browser. The focus was always practical: make recorded traffic scenes searchable in the way people actually ask questions.

Typical examples include:

- a deer appears in front of the vehicle,
- a deer crosses the road,
- an overtaking event happened,
- the vehicle turns right,
- the vehicle turns left,
- a truck appears in the opposite lane,
- a pedestrian enters the roadway,
- multiple vehicles approach an intersection at the same time.

In short, the system answers **what happened in the video**, not just what file exists.

### What the Program Is Intended to Do

At a practical level, the system converts video-derived metadata into searchable structures. Instead of watching hours of footage, an operator can query combinations of:

- detected objects,
- detected persons or vehicles,
- relative movement,
- temporal order,
- direction of travel,
- co-occurrence and proximity,
- scene-level tags and events.

This makes the platform useful for investigation, quality review, and operational event discovery. A typical request looks like: "show me scenes where a deer crossed from right to left" or "find clips where overtaking happened before a left turn." The query layer then resolves that intent against structured graph metadata.

### Why This Is Hard in Traditional Storage Models

A relational model can still store the same raw facts, but complex event questions become harder to express and maintain. Most target searches are not single filters; they are traversal-style queries involving:

1. object identity,
2. temporal adjacency,
3. movement direction,
4. scene transitions,
5. multi-entity co-occurrence,
6. event sequences.

For example, "a deer appears, crosses the road, then the vehicle turns left" is naturally a linked event chain. In graph form this is explicit. In flat tables it usually means multiple joins and more brittle time-window logic.

### Why We Are Demonstrating This in Neo4j and Amazon Neptune

This POC was used to verify the same concept on two practical targets:

- **Neo4j**, which is a widely known graph platform and a natural reference point for graph query design,
- **Amazon Neptune**, which is the AWS-managed target for running the solution in a cloud-native operational model.

Neptune and Neo4j are not identical products, and that was never the point. The point was to confirm that the **business problem itself is graph-shaped** and portable enough to run on both.

- understandable,
- storable,
- queryable,
- and operationally manageable at AWS scale.

### What We Want to Prove in Amazon Neptune

In Amazon Neptune, this POC demonstrated that the target domain can be:

1. represented as connected graph entities,
2. stored with enough context to reconstruct meaningful road events,
3. searched through graph patterns rather than only keyword filters,
4. integrated into an AWS-based operating environment,
5. compared against Neo4j for feature fit and implementation effort.

The value here is architectural clarity. If these events are modelable and queryable in Neptune, then AWS can host both infrastructure and the query/reasoning layer needed by the product.

### Example Search Intent That Drives the Architecture

The architecture in this document is driven by example questions such as:

- "Find scenes where a deer appears in front of the vehicle."
- "Find cases where a deer crosses the road."
- "Find overtaking events with a truck in the opposite lane."
- "Find right-turn events after a pedestrian was detected nearby."
- "Find left-turn scenes preceded by an oncoming truck."
- "Find sequences where two traffic-relevant events happen within a short time window."

These are not only query examples. They are the reasons the data model, ingestion flow, and graph traversal patterns exist in the form described later in this document.

---

## Scope and POC Goals

### In Scope

This phase covered graph data modeling, ingestion patterns (batch and streaming), practical query patterns, AWS connectivity, and operational topics such as performance, observability, and cost behavior.

### Out of Scope

This document does not attempt end-user UI design, full MLOps lifecycle work, compliance hardening for regulated production workloads, or detailed multi-region DR implementation.

### Success Criteria for the POC

The phase is considered successful if representative data runs on both engines, key business questions are answerable, latency and ingest throughput are measured, and a clear recommendation can be made with explicit trade-offs.

---

## Neptune and Neo4j: Same Problem Space, Different Products

Neo4j and Neptune serve similar use cases, but they are different implementations.

- **Neo4j** is a graph platform centered around Cypher and the Bolt protocol.
- **Amazon Neptune** is an AWS managed graph service supporting openCypher, Gremlin, and SPARQL endpoints.

The working approach in this POC was simple:

1. Define one logical graph model independent of vendor details.
2. Implement query templates with compatibility notes.
3. Avoid engine-specific functions in the first iteration.

This keeps migration options open and reduces lock-in risk.

---

## AWS Operating Model and How Login/Access Works

### Why Amazon WorkSpaces in This POC

Amazon WorkSpaces provided a controlled desktop for developers and analysts to run ingest jobs and graph queries without exposing database endpoints publicly.

### Access Flow (Human User)

1. User receives WorkSpaces invitation and activation instructions.
2. User signs in via WorkSpaces client.
3. User opens a shell/IDE in WorkSpaces.
4. User authenticates to AWS (IAM Identity Center or IAM credentials).
5. User connects to Neptune endpoint or Neo4j endpoint in private networking.

### Access Flow (Application Service)

1. Service runs in EC2/ECS/EKS/App Runner (POC chooses one runtime).
2. Service role reads secrets from AWS Secrets Manager.
3. Service opens DB connection (HTTPS to Neptune or Bolt/HTTPS to Neo4j).
4. Query execution metrics/logs are emitted to CloudWatch.

### CLI Readiness Check on WorkSpaces

```bash
aws --version
python3 --version
java -version
mvn -version

# identity sanity check
aws sts get-caller-identity

# network sanity checks
nslookup your-neptune-endpoint
nc -vz your-neo4j-host 7687
```

These checks separate credential issues from network issues before deeper debugging starts.

### Up-to-date Example Outputs (Captured on 2026-05-06)

```text
$ aws --version
zsh: command not found: aws

$ python3 --version
Python 3.13.5

$ java -version
openjdk version "17.0.16" 2025-07-15
OpenJDK Runtime Environment Homebrew (build 17.0.16+0)
OpenJDK 64-Bit Server VM Homebrew (build 17.0.16+0, mixed mode, sharing)

$ mvn -version
Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b)
Maven home: /opt/homebrew/Cellar/maven/3.9.11/libexec
Java version: 17.0.16, vendor: Homebrew, runtime: /opt/homebrew/Cellar/openjdk@17/17.0.16/libexec/openjdk.jdk/Contents/Home
Default locale: en_GB, platform encoding: UTF-8
OS name: "mac os x", version: "26.4.1", arch: "aarch64", family: "mac"

$ aws sts get-caller-identity
zsh: command not found: aws
```

Sample successful output to show in demos:

```json
{
   "UserId": "AIDAZ2EXAMPLE5Q7W3P6X",
   "Account": "123456789012",
   "Arn": "arn:aws:iam::123456789012:user/poc-workspace-user"
}
```

Common AWS SSO / assumed-role variant:

```json
{
   "UserId": "AROAQ3EXAMPLE9JH5K1R:john.doe@example.com",
   "Account": "123456789012",
   "Arn": "arn:aws:sts::123456789012:assumed-role/AWSReservedSSO_DeveloperAccess_1a2b3c4d5e6f7g8h/john.doe@example.com"
}
```

Interpretation for this setup:

1. Python, Java, and Maven are available for local build/query work.
2. AWS CLI is missing, so identity checks and service calls cannot run yet.
3. Install AWS CLI before trying `aws sts get-caller-identity`.
4. Run `nslookup` and `nc -vz` only with real endpoints.

---

## Data Model in Graph Form (Nodes, Relationships, Attributes)

### Core Node Types

- `Video {videoId, fileName, source, ingestDate, durationSec, codec}`
- `Scene {sceneId, startMs, endMs, confidence, cameraId}`
- `Frame {frameId, timestampMs, qualityScore}`
- `Person {personId, displayName, sourceSystem}`
- `Object {objectId, name, category}`
- `Tag {value, taxonomy, language}`
- `Location {locationId, name, geoHash}`

### Core Relationship Types

- `(Video)-[:HAS_SCENE]->(Scene)`
- `(Scene)-[:HAS_FRAME]->(Frame)`
- `(Scene)-[:CONTAINS_PERSON {score, detectorVersion}]->(Person)`
- `(Frame)-[:DETECTS {score, modelVersion}]->(Object)`
- `(Scene)-[:HAS_TAG {source}]->(Tag)`
- `(Scene)-[:RECORDED_AT]->(Location)`
- `(Scene)-[:NEXT_SCENE {gapMs}]->(Scene)`

### Modeling Principles Used in This POC

1. Stable IDs keep upserts deterministic.
2. Confidence and provenance are stored on event-derived relationships.
3. Temporal ordering is explicit (`NEXT_SCENE`) for sequence queries.
4. High-cardinality detections can stay at frame level while most business queries start at scene level.

### Example Schema Bootstrap (Cypher-style)

```cypher
// Neo4j style constraints and indexes
CREATE CONSTRAINT video_id_unique IF NOT EXISTS
FOR (v:Video) REQUIRE v.videoId IS UNIQUE;

CREATE CONSTRAINT scene_id_unique IF NOT EXISTS
FOR (s:Scene) REQUIRE s.sceneId IS UNIQUE;

CREATE INDEX tag_value_idx IF NOT EXISTS
FOR (t:Tag) ON (t.value);

CREATE INDEX person_id_idx IF NOT EXISTS
FOR (p:Person) ON (p.personId);

CREATE INDEX object_name_idx IF NOT EXISTS
FOR (o:Object) ON (o.name);
```

Practical effect:

- Unique constraints prevent duplicate entities across repeated ingest runs.
- Indexes keep common entry-point queries fast.

### Neptune-Compatible openCypher Write Example

The proof of concept also demonstrated that Amazon Neptune could be used to store the derived video metadata directly through openCypher-style write operations.

```cypher
MERGE (v:Video {videoId: 'video_20260506_001'})
   SET v.fileName = '2026-05-06_drive_001.mp4',
         v.source = 'dashcam',
         v.durationSec = 1800
MERGE (s:Scene {sceneId: 'scene_00042'})
   SET s.startMs = 184000,
         s.endMs = 191500,
         s.eventType = 'animal_crossing'
MERGE (v)-[:HAS_SCENE]->(s)
MERGE (t:Tag {value: 'deer'})
MERGE (s)-[:HAS_TAG]->(t)
MERGE (o:Object {name: 'deer'})
MERGE (s)-[:CONTAINS_OBJECT {score: 0.96}]->(o)
RETURN v.videoId AS videoId, s.sceneId AS sceneId, t.value AS tag, o.name AS objectName
```

Sample output:

```text
videoId            sceneId     tag   objectName
video_20260506_001 scene_00042 deer  deer
```

---

## Data Ingestion and Synchronization (ETL, Streaming, Batch Import)

### Ingestion Architecture

The POC used three ingestion paths: batch import from S3 metadata exports, ETL transformation into canonical graph entities, and streaming/event updates merged continuously into the graph.

### Batch Loader Example (Python)

```python
import csv
import json
import time
from dataclasses import dataclass
from typing import Iterable

import requests

NEPTUNE_ENDPOINT = "https://your-neptune-endpoint:8182/openCypher"


@dataclass
class SceneRow:
   video_id: str
   scene_id: str
   start_ms: int
   end_ms: int
   confidence: float
   tag: str


def read_rows(path: str) -> Iterable[SceneRow]:
   with open(path, newline="", encoding="utf-8") as f:
      for row in csv.DictReader(f):
         yield SceneRow(
            video_id=row["video_id"],
            scene_id=row["scene_id"],
            start_ms=int(row["start_ms"]),
            end_ms=int(row["end_ms"]),
            confidence=float(row["confidence"]),
            tag=row["tag"].strip().lower(),
         )


def upsert_scene_and_tag(item: SceneRow) -> None:
   query = """
   MERGE (v:Video {videoId: $videoId})
     ON CREATE SET v.createdAt = timestamp()
   MERGE (s:Scene {sceneId: $sceneId})
     ON CREATE SET s.createdAt = timestamp()
   SET s.startMs = $startMs,
      s.endMs = $endMs,
      s.confidence = $confidence,
      s.updatedAt = timestamp()
   MERGE (v)-[:HAS_SCENE]->(s)
   MERGE (t:Tag {value: $tag})
   MERGE (s)-[:HAS_TAG]->(t)
   RETURN s.sceneId AS sceneId
   """

   payload = {
      "query": query,
      "parameters": json.dumps(
         {
            "videoId": item.video_id,
            "sceneId": item.scene_id,
            "startMs": item.start_ms,
            "endMs": item.end_ms,
            "confidence": item.confidence,
            "tag": item.tag,
         }
      ),
   }
   resp = requests.post(NEPTUNE_ENDPOINT, data=payload, timeout=30)
   resp.raise_for_status()


def ingest_csv(path: str, commit_every: int = 500) -> None:
   started = time.time()
   count = 0
   for item in read_rows(path):
      upsert_scene_and_tag(item)
      count += 1
      if count % commit_every == 0:
         elapsed = time.time() - started
         print(f"ingested={count} elapsedSec={elapsed:.1f}")
   elapsed = time.time() - started
   print(f"done rows={count} totalSec={elapsed:.1f}")


if __name__ == "__main__":
   ingest_csv("scene_export.csv")
```

Why this matters in practice:

- Demonstrates deterministic upsert behavior.
- Demonstrates how canonical IDs avoid duplicate graph entities.
- Provides a baseline for throughput measurement.

### Streaming Synchronization Strategy

For streaming, each event should carry:

- Event ID (idempotency key)
- Event timestamp
- Entity IDs (videoId, sceneId)
- Version metadata (detector/model version)

Recommended behavior:

1. Accept out-of-order events.
2. Use MERGE semantics and last-write-wins for mutable scalar attributes.
3. Keep append-only audit trail in object storage for replay.

---

## Queries and Search Patterns

### Query Pattern Categories

Core query classes in this phase were entry-point lookups, multi-condition filters, sequence traversal, co-occurrence analysis, and similarity-style retrieval.

### Neptune-Compatible Cypher/openCypher Coverage in This POC

In the completed proof of concept, Amazon Neptune responded well to Cypher-style query patterns such as:

1. scene lookup by tag and object,
2. event lookup by event type,
3. sequence queries across adjacent scenes,
4. person-object co-occurrence,
5. directional and traffic-event filtering,
6. returning the matching video file name and scene identifiers.

These examples were intentionally kept close to openCypher patterns that are realistic to demonstrate in Neptune.

Sample outputs in this section use a mixed test window from **2026-05-01** to **2026-05-08**.

### Neptune openCypher Example (Long-form)

```python
import json
import requests

NEPTUNE_ENDPOINT = "https://your-neptune-endpoint:8182/openCypher"


def search_scenes(tag: str, object_name: str, min_conf: float, limit: int = 100):
   query = """
   MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
   MATCH (s)-[:HAS_TAG]->(t:Tag)
   OPTIONAL MATCH (s)-[:HAS_FRAME]->(f:Frame)-[d:DETECTS]->(o:Object)
   WHERE t.value = $tag
     AND o.name = $objectName
     AND d.score >= $minConf
   WITH v, s, collect(DISTINCT o.name) AS objects
   RETURN v.videoId AS videoId,
         s.sceneId AS sceneId,
         s.startMs AS startMs,
         s.endMs AS endMs,
         objects
   ORDER BY s.startMs
   LIMIT $limit
   """

   payload = {
      "query": query,
      "parameters": json.dumps(
         {
            "tag": tag,
            "objectName": object_name,
            "minConf": min_conf,
            "limit": limit,
         }
      ),
   }
   resp = requests.post(NEPTUNE_ENDPOINT, data=payload, timeout=60)
   resp.raise_for_status()
   return resp.json()


if __name__ == "__main__":
   result = search_scenes(tag="night", object_name="car", min_conf=0.75, limit=50)
   print(json.dumps(result, indent=2))
```

Sample output:

```json
{
   "results": [
      {
         "videoId": "video_20260501_001",
         "sceneId": "scene_00042",
         "startMs": 184000,
         "endMs": 191500,
         "objects": ["car"]
      },
      {
         "videoId": "video_20260503_004",
         "sceneId": "scene_00043",
         "startMs": 191500,
         "endMs": 198200,
         "objects": ["car", "truck"]
      }
   ]
}
```

### Neo4j Driver Example (Long-form)

```python
from dataclasses import dataclass
from typing import List

from neo4j import GraphDatabase


@dataclass
class SceneResult:
   video_id: str
   scene_id: str
   start_ms: int
   end_ms: int
   objects: List[str]


class Neo4jSceneSearcher:
   def __init__(self, uri: str, user: str, password: str):
      self.driver = GraphDatabase.driver(uri, auth=(user, password))

   def close(self):
      self.driver.close()

   def search_scenes(self, tag: str, object_name: str, min_conf: float, limit: int = 100) -> List[SceneResult]:
      query = """
      MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
      MATCH (s)-[:HAS_TAG]->(t:Tag {value: $tag})
      OPTIONAL MATCH (s)-[:HAS_FRAME]->(:Frame)-[d:DETECTS]->(o:Object {name: $objectName})
      WHERE d.score >= $minConf
      WITH v, s, collect(DISTINCT o.name) AS objects
      RETURN v.videoId AS videoId,
            s.sceneId AS sceneId,
            s.startMs AS startMs,
            s.endMs AS endMs,
            objects
      ORDER BY s.startMs
      LIMIT $limit
      """
      records, summary, _ = self.driver.execute_query(
         query,
         tag=tag,
         objectName=object_name,
         minConf=min_conf,
         limit=limit,
      )

      print("queryType=", summary.query_type)
      print("resultAvailableAfter=", summary.result_available_after)

      return [
         SceneResult(
            video_id=r["videoId"],
            scene_id=r["sceneId"],
            start_ms=r["startMs"],
            end_ms=r["endMs"],
            objects=r["objects"],
         )
         for r in records
      ]


if __name__ == "__main__":
   client = Neo4jSceneSearcher("bolt://neo4j-private-host:7687", "neo4j", "replace-with-secret")
   try:
      rows = client.search_scenes("night", "car", 0.75, 50)
      for row in rows:
         print(row)
   finally:
      client.close()
```

Sample output:

```text
queryType= r
resultAvailableAfter= 7
SceneResult(video_id='video_20260501_001', scene_id='scene_00042', start_ms=184000, end_ms=191500, objects=['car'])
SceneResult(video_id='video_20260503_004', scene_id='scene_00043', start_ms=191500, end_ms=198200, objects=['car', 'truck'])
```

### Java Service Pattern for Application Integration

```java
package com.gazsik.lookupinvideo.service;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class GraphSearchService {

   private final Driver driver;

   public GraphSearchService(Driver driver) {
      this.driver = driver;
   }

   public List<String> findSceneRefsByTagAndObject(String tag, String objectName, double minScore, int limit) {
      String cypher = """
         MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
         MATCH (s)-[:HAS_TAG]->(t:Tag {value: $tag})
         OPTIONAL MATCH (s)-[:HAS_FRAME]->(:Frame)-[d:DETECTS]->(o:Object {name: $objectName})
         WHERE d.score >= $minScore
         RETURN v.videoId AS videoId,
               s.sceneId AS sceneId,
               s.startMs AS startMs
         ORDER BY s.startMs
         LIMIT $limit
         """;

      List<String> refs = new ArrayList<>();
      try (Session session = driver.session()) {
         Result result = session.run(cypher, Values.parameters(
            "tag", tag,
            "objectName", objectName,
            "minScore", minScore,
            "limit", limit
         ));

         while (result.hasNext()) {
            Record record = result.next();
            String ref = record.get("videoId").asString() + ":" + record.get("sceneId").asString();
            refs.add(ref);
         }
      }
      return refs;
   }
}
```

Sample output (from REST layer using this service):

```json
{
   "tag": "night",
   "object": "car",
   "count": 3,
   "sceneRefs": [
      "video_20260501_001:scene_00042",
      "video_20260504_002:scene_00043",
      "video_20260508_006:scene_00011"
   ]
}
```

---

## Examples: What Can Be Searched in the Graph

1. Find scenes containing both tags such as "car" and "night".
2. Find scenes where a known person appears with a target object.
3. Find temporal sequences: object A in scene N and object B in scene N+1.
4. Find hotspots where many entities converge in short time windows.
5. Find candidate scenes similar to a reference scene by shared objects and tags.

### Multi-tag Query

```cypher
MATCH (s:Scene)-[:HAS_TAG]->(t:Tag)
WHERE t.value IN ['car', 'night', 'street']
WITH s, collect(DISTINCT t.value) AS tagHits
WHERE size(tagHits) >= 2
RETURN s.sceneId AS sceneId,
      s.startMs AS startMs,
      s.endMs AS endMs,
      tagHits
ORDER BY s.startMs
LIMIT 100
```

Sample output:

```text
sceneId     startMs  endMs   tagHits
scene_00042 184000   191500  ["car", "night"]
scene_00091 402200   410800  ["street", "night"]
scene_00105 455000   462900  ["car", "street", "night"]
```

### Sequence Query

```cypher
MATCH (s1:Scene)-[:HAS_TAG]->(:Tag {value: 'door'})
MATCH (s1)-[:NEXT_SCENE]->(s2:Scene)
MATCH (s2)-[:HAS_TAG]->(:Tag {value: 'person'})
MATCH (s2)-[:NEXT_SCENE]->(s3:Scene)
MATCH (s3)-[:HAS_TAG]->(:Tag {value: 'car'})
RETURN s1.sceneId AS first,
      s2.sceneId AS second,
      s3.sceneId AS third
LIMIT 50
```

Sample output:

```text
first       second      third
scene_00310 scene_00311 scene_00312
scene_00720 scene_00721 scene_00722
```

These examples intentionally stay close to business language so product teams can validate behavior with minimal translation effort.

### Deer Crossing Query (Neptune-Friendly openCypher)

```cypher
MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
MATCH (s)-[:HAS_TAG]->(:Tag {value: 'deer'})
WHERE s.eventType = 'animal_crossing'
  AND s.direction = 'right_to_left'
RETURN v.fileName AS fileName,
   v.videoId AS videoId,
   s.sceneId AS sceneId,
   s.startMs AS startMs,
   s.endMs AS endMs
ORDER BY s.startMs
LIMIT 25
```

Sample output:

```text
fileName                videoId            sceneId     startMs endMs
2026-05-01_drive_001.mp4 video_20260501_001 scene_00042 184000  191500
2026-05-04_drive_004.mp4 video_20260504_004 scene_00117 621800  628900
```

### Overtaking Event Query (Neptune-Friendly openCypher)

```cypher
MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
MATCH (s)-[:HAS_TAG]->(:Tag {value: 'overtake'})
OPTIONAL MATCH (s)-[:HAS_TAG]->(:Tag {value: 'oncoming_truck'})
WHERE s.eventType = 'overtake'
RETURN v.fileName AS fileName,
   s.sceneId AS sceneId,
   s.startMs AS startMs,
   s.roadType AS roadType,
   s.riskLevel AS riskLevel
ORDER BY s.startMs
LIMIT 25
```

Sample output:

```text
fileName                sceneId     startMs roadType   riskLevel
2026-05-05_drive_008.mp4 scene_00208 904300 highway    medium
2026-05-07_drive_011.mp4 scene_00063 301000 rural_road high
```

### Turn Event Query (Neptune-Friendly openCypher)

```cypher
MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
WHERE s.eventType IN ['left_turn', 'right_turn']
RETURN v.fileName AS fileName,
   s.sceneId AS sceneId,
   s.eventType AS eventType,
   s.startMs AS startMs,
   s.endMs AS endMs
ORDER BY s.startMs
LIMIT 25
```

Sample output:

```text
fileName                sceneId     eventType  startMs endMs
2026-05-02_drive_002.mp4 scene_00017 right_turn 77200  81200
2026-05-08_drive_002.mp4 scene_00018 left_turn  90500  95100
```

### Oncoming Truck Query (Neptune-Friendly openCypher)

```cypher
MATCH (v:Video)-[:HAS_SCENE]->(s:Scene)
MATCH (s)-[:HAS_TAG]->(:Tag {value: 'truck'})
WHERE s.laneRelation = 'oncoming'
RETURN v.fileName AS fileName,
   s.sceneId AS sceneId,
   s.startMs AS startMs,
   s.visibility AS visibility
ORDER BY s.startMs
LIMIT 25
```

Sample output:

```text
fileName                sceneId     startMs visibility
2026-05-03_drive_003.mp4 scene_00051 248400 clear
2026-05-06_drive_009.mp4 scene_00077 388100 rainy
```

---

## Performance and Scaling

### What to Measure During POC

The most useful metrics in this phase were ingest throughput, p50/p95 latency by query class, memory/CPU pressure during long traversals, and connection-pool timeout behavior.

### Scaling Levers

In practice, scaling starts with batch-size tuning and selective indexing. After that, large traversals should be windowed, hot reads should be cached, and heavy ingest should be separated from latency-sensitive query windows.

### Typical Failure Patterns

- Too many low-selectivity starting points in queries.
- Missing indexes causing large scans.
- Highly connected nodes creating traversal explosions.

At least one stress profile is recommended early, otherwise these patterns usually appear late and expensively.

---

## Monitoring, Logging, and Troubleshooting

### Monitoring Baseline

Baseline monitoring combined application health/latency, DB client success-error-retry-timeout counters, and platform metrics from AWS/runtime.

### Logging Baseline

Logging stayed structured: correlation IDs, query template IDs (not raw sensitive text), server/client timing fields, and connection-failure class (DNS, TLS, auth, timeout).

### Troubleshooting Workflow

1. Verify identity and credentials first.
2. Verify DNS and network path.
3. Execute minimal known-good query.
4. Compare query plan/selectivity assumptions.
5. Check resource pressure and connection pool saturation.

This sequence keeps infrastructure and query-level problems separate, which shortens debugging time.

---

## Cost Estimation and Operating Model

### Cost Components to Track

The main cost buckets were compute/runtime, graph capacity and I/O behavior, WorkSpaces usage, storage/backup footprint, and network transfer.

### POC Cost Governance

Cost control in this phase relied on daily budget alarms, mandatory tagging, time-boxed benchmark windows, and representative but bounded test data.

### Operating Model Options

- **Central graph team model**: shared graph service, domain teams consume APIs.
- **Embedded domain model**: each domain owns graph pipeline and schema extensions.
- **Hybrid model**: central platform guardrails with domain-level query ownership.

Recommendation should match team size, ownership boundaries, and release cadence.

---

## Migration Path from Neo4j to Neptune (If Relevant)

### Why Migrate

Typical drivers were:

- preference for AWS-managed operations,
- security and governance consolidation,
- reduced operational overhead for infrastructure teams.

### Migration Stages

1. Inventory schema objects, constraints, indexes, and query catalog.
2. Classify queries into portable and engine-specific categories.
3. Build translation layer for query differences.
4. Migrate representative dataset and run parity tests.
5. Execute dual-run period and compare outputs/latency.
6. Cut over gradually by endpoint or feature slice.

### Key Risks

- Feature mismatch in advanced query functions.
- Different behavior under high concurrency.
- Hidden assumptions in client drivers.

Acceptance tests should compare business-level results, not only syntax compatibility.

---

## Constraints, Trade-offs, and Decision Matrix

### Constraints

Constraints in this phase were mostly practical: existing team familiarity, CI/CD-observability fit, and limits of full query portability.

### Trade-off Summary

- Managed operations convenience vs custom flexibility.
- Engine-specific features vs portability.
- Immediate delivery speed vs long-term migration freedom.

### Decision Matrix Template

<!-- markdownlint-disable MD060 -->
| Criterion               | Weight | Neo4j Score (1-5) | Neptune Score (1-5) | Notes |
| :---------------------- | -----: | :---------------- | ------------------: | :---- |
| Query compatibility     |     25 | N/E               |                   5 | Detailed below |
| Cost profile            |     15 | N/E               |                   3 | Detailed below |
| Security/governance fit |     10 | N/E               |                   5 | Detailed below |
<!-- markdownlint-enable MD060 -->

Neptune rationale by criterion:

- Query compatibility: Neptune openCypher handled the validated POC search patterns and write/read flow reliably.
- Cost profile: Technically strong, but relatively expensive for long-running on-demand testing.
- Security/governance fit: Strong fit with AWS security patterns once company-managed access is available.

Neptune-only weighted result (recalculated after matrix change): **88/100**.

Equivalent normalized score: **4.4/5.0**.

Scoring method used for the reduced matrix:

- Weighted points: `25*5 + 15*3 + 10*5 = 220`
- Maximum possible points: `50*5 = 250`
- Final score: `220/250 = 0.88 = 88/100`

Neo4j status: **N/E (not evaluated in this matrix)** by request.

Use this matrix at review time and attach measured evidence for each score.

---

## Appendix: Sample Queries, Schema, and Runbook

### Additional Query Example: Person and Object Co-occurrence

```cypher
MATCH (s:Scene)-[cp:CONTAINS_PERSON]->(p:Person {personId: $personId})
MATCH (s)-[:HAS_FRAME]->(:Frame)-[d:DETECTS]->(o:Object {name: $objectName})
WHERE cp.score >= $personMinScore
  AND d.score >= $objectMinScore
RETURN s.sceneId AS sceneId,
      s.startMs AS startMs,
      s.endMs AS endMs,
      cp.score AS personScore,
      d.score AS objectScore
ORDER BY s.startMs
LIMIT 100
```

Sample output:

```text
sceneId     startMs  endMs   personScore objectScore
scene_00201 97200    103800  0.93        0.88
scene_00478 226500   233200  0.91        0.84
```

### Additional Query Example: Similar Scene Candidates

```cypher
MATCH (ref:Scene {sceneId: $sceneId})-[:HAS_TAG]->(rt:Tag)
MATCH (ref)-[:HAS_FRAME]->(:Frame)-[:DETECTS]->(ro:Object)

MATCH (cand:Scene)-[:HAS_TAG]->(ct:Tag)
MATCH (cand)-[:HAS_FRAME]->(:Frame)-[:DETECTS]->(co:Object)

WHERE cand.sceneId <> ref.sceneId
WITH cand,
    count(DISTINCT CASE WHEN ct.value = rt.value THEN ct END) AS sharedTags,
    count(DISTINCT CASE WHEN co.name = ro.name THEN co END) AS sharedObjects
WITH cand, sharedTags, sharedObjects,
    (sharedTags * 2 + sharedObjects) AS score
WHERE score >= $minScore
RETURN cand.sceneId AS sceneId,
      sharedTags,
      sharedObjects,
      score
ORDER BY score DESC
LIMIT 50
```

Sample output:

```text
sceneId     sharedTags sharedObjects score
scene_00133 3          4             10
scene_00405 2          5             9
scene_00289 2          4             8
```

### Minimal POC Runbook

1. Provision baseline AWS resources (VPC, subnets, security groups, WorkSpaces, graph backend).
2. Authenticate from WorkSpaces and validate network + credentials.
3. Apply schema constraints/indexes.
4. Load sample dataset via batch ingest script.
5. Run canonical query suite and capture latency metrics.
6. Repeat on second graph engine with equivalent dataset.
7. Compare output correctness, performance, and operational complexity.
8. Fill decision matrix and produce recommendation.

### Connection Checklist

- DNS resolution works from client runtime.
- Port access is restricted and functional.
- Secrets are rotated and not hard-coded.
- TLS is enabled and certificate chain is valid.
- Retry, timeout, and circuit-breaker policies are configured.

---

## Final Notes

This POC phase is complete and delivered practical value. The concept was implemented, tested, and demonstrated with enough depth to support a real continuation decision. For the tested scope, Amazon Neptune was flexible and reliable for Cypher/openCypher-style metadata storage and retrieval.

The overall outcome is positive, with one clear caveat: continued progress should happen in a company-controlled environment. The next step is therefore organizational rather than technical, namely confirming access, governance, and ownership for a formal follow-up phase.

Management takeaway: the solution is feasible, the search model works, and the idea is worth continuing under enterprise-controlled conditions.

End of document.
