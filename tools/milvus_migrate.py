#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Milvus 集合导出 / 导入（零依赖，只用 Python 标准库 + Milvus REST v2 接口）。

适用场景：把某个集合（连向量一起）从一台机器的 Milvus 搬到另一台机器的 Milvus。
不需要 pymilvus，也不需要在目标机器上装 PowerShell —— 只要有 Python 3.8+。

用法：
  # 在源机器导出（首行写入 __schema 元信息，便于导入端自动建集合）
  python milvus_migrate.py export --collection campus_repair_knowledge --output milvus.jsonl

  # 在目标机器导入（自动建集合 + 建索引 + 加载；集合不存在时用 --create）
  python milvus_migrate.py import --source milvus.jsonl \
      --milvus http://127.0.0.1:19530 --collection campus_repair_knowledge --create

  # 只演练
  python milvus_migrate.py import --source milvus.jsonl --dry-run

注意事项：
  * Milvus 2.4 的 REST v2 接口在 19530 端口（9091 只有 /healthz），所以 --milvus 直接写 19530；
  * 跨实例导入建议换一个集合名（--collection），Milvus 插入相同主键不会去重，会变成两条；
  * 目标端 Milvus 版本需 ≥ 源端；embedding 模型与维度必须一致（本项目为 doubao-embedding-text-240715 / 2560 维）。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request


def call(base, path, payload=None, timeout=600):
    """调用 Milvus REST v2 接口，返回解析后的 JSON。"""
    body = json.dumps(payload if payload is not None else {}).encode("utf-8")
    request = urllib.request.Request(
        base.rstrip("/") + path, data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "ignore")
        raise RuntimeError("HTTP %s: %s" % (error.code, detail))
    except urllib.error.URLError as error:
        raise RuntimeError("连接失败（检查地址端口与 Milvus 是否启动）：%s" % error)


def param_of(field, key):
    for item in field.get("params") or []:
        if item.get("key") == key:
            return item.get("value")
    return None


def do_export(args):
    describe = call(args.milvus, "/v2/vectordb/collections/describe",
                    {"collectionName": args.collection})
    if describe.get("code") != 0:
        raise RuntimeError("describe 失败：%s" % json.dumps(describe, ensure_ascii=False))
    fields = describe["data"]["fields"]
    names = [field["name"] for field in fields]
    vector_field = next(field for field in fields if field["type"] == "FloatVector")
    dimension = int(param_of(vector_field, "dim") or 0)
    print("集合 %s：字段 %s，向量维度 %s" % (args.collection, ", ".join(names), dimension))

    rows, offset = [], 0
    while True:
        page = call(args.milvus, "/v2/vectordb/entities/query", {
            "collectionName": args.collection, "filter": "",
            "outputFields": names, "limit": args.page_size, "offset": offset,
        })
        if page.get("code") != 0:
            raise RuntimeError("query 失败：%s" % json.dumps(page, ensure_ascii=False))
        batch = [row for row in (page.get("data") or []) if row]
        if not batch:
            break
        rows.extend(batch)
        print("  已读取 %d 条 ..." % len(rows))
        if len(batch) < args.page_size:
            break
        offset += args.page_size
        if offset >= 16384:
            print("  已达 query offset 上限 16384；更大数据量请改用 pymilvus 的 query_iterator")
            break

    with open(args.output, "w", encoding="utf-8") as handle:
        handle.write(json.dumps({"__schema": {
            "collectionName": args.collection, "dimension": dimension, "fields": fields,
            "entityCount": len(rows), "exportedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        }}, ensure_ascii=False) + "\n")
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print("导出完成：%d 条 -> %s" % (len(rows), args.output))


def do_import(args):
    with open(args.source, "r", encoding="utf-8") as handle:
        lines = [line.rstrip("\n") for line in handle if line.strip()]
    if len(lines) < 2:
        raise RuntimeError("导出文件为空：%s" % args.source)
    schema = json.loads(lines[0])["__schema"]
    entities = lines[1:]
    collection = args.collection or schema["collectionName"]
    print("导入 %d 条 -> %s 的集合 %s（维度 %s）"
          % (len(entities), args.milvus, collection, schema.get("dimension")))
    if collection == schema["collectionName"]:
        print("提示：目标集合名与源相同；若目标已存在同名集合会产生重复数据，建议换名。")

    if args.dry_run:
        print("（--dry-run）源文件结构：")
        for field in schema["fields"]:
            extra = " ".join("%s=%s" % (item["key"], item["value"])
                             for item in (field.get("params") or [])
                             if item.get("key") in ("dim", "max_length"))
            print("    %-10s %-12s %s" % (field["name"], field["type"], extra))
        return

    existing = (call(args.milvus, "/v2/vectordb/collections/list").get("data") or [])
    if collection in existing:
        print("  目标集合已存在，直接追加数据")
    elif not args.create:
        raise RuntimeError("目标实例没有集合 %s，请加 --create" % collection)
    else:
        field_defs = []
        for field in schema["fields"]:
            definition = {"fieldName": field["name"], "dataType": field["type"]}
            if field.get("primaryKey"):
                definition["isPrimary"] = True
            element = {item["key"]: str(item["value"]) for item in (field.get("params") or []) if item.get("key")}
            if element:
                definition["elementTypeParams"] = element
            field_defs.append(definition)
        vector_name = next(field["name"] for field in schema["fields"] if field["type"] == "FloatVector")
        created = call(args.milvus, "/v2/vectordb/collections/create", {
            "collectionName": collection,
            "schema": {"autoId": False, "enableDynamicField": False, "fields": field_defs},
            "indexParams": [{"metricType": args.metric, "fieldName": vector_name,
                             "indexName": "vector_index", "params": {"index_type": "AUTOINDEX"}}],
        })
        if created.get("code") != 0:
            raise RuntimeError("建集合失败：%s" % json.dumps(created, ensure_ascii=False))
        print("  已创建集合 %s（含向量索引，metric=%s）" % (collection, args.metric))

    total = 0
    for start in range(0, len(entities), args.batch_size):
        batch = entities[start:start + args.batch_size]
        # 用原始实体文本拼 data 数组，避免二次序列化改变结构
        body = '{"collectionName":"%s","data":[%s]}' % (collection, ",".join(batch))
        request = urllib.request.Request(
            args.milvus.rstrip("/") + "/v2/vectordb/entities/insert",
            data=body.encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(request, timeout=600) as response:
            result = json.loads(response.read().decode("utf-8"))
        if result.get("code") != 0:
            raise RuntimeError("插入失败（第 %d 条起）：%s" % (start + 1, json.dumps(result, ensure_ascii=False)))
        total += result["data"]["insertCount"]
        print("  已插入 %d/%d" % (total, len(entities)))

    call(args.milvus, "/v2/vectordb/collections/load", {"collectionName": collection})
    time.sleep(2)
    count = call(args.milvus, "/v2/vectordb/entities/query",
                 {"collectionName": collection, "filter": "", "outputFields": ["count(*)"]})
    print("导入完成：写入 %d 条，目标集合当前实体数 %s" % (total, json.dumps(count.get("data"), ensure_ascii=False)))


def main():
    parser = argparse.ArgumentParser(description="Milvus 集合导出/导入（REST v2，零依赖）")
    subparsers = parser.add_subparsers(dest="command", required=True)

    export_parser = subparsers.add_parser("export", help="导出集合为 JSONL（含向量）")
    export_parser.add_argument("--milvus", default="http://127.0.0.1:19530")
    export_parser.add_argument("--collection", default="campus_repair_knowledge")
    export_parser.add_argument("--output", default="milvus-export.jsonl")
    export_parser.add_argument("--page-size", type=int, default=500)
    export_parser.set_defaults(func=do_export)

    import_parser = subparsers.add_parser("import", help="从 JSONL 导入到目标实例")
    import_parser.add_argument("--milvus", default="http://127.0.0.1:19530")
    import_parser.add_argument("--source", required=True)
    import_parser.add_argument("--collection", default="")
    import_parser.add_argument("--create", action="store_true", help="目标无此集合时按源结构自动创建")
    import_parser.add_argument("--batch-size", type=int, default=100)
    import_parser.add_argument("--metric", default="COSINE")
    import_parser.add_argument("--dry-run", action="store_true")
    import_parser.set_defaults(func=do_import)

    args = parser.parse_args()
    try:
        args.func(args)
    except Exception as error:  # noqa: BLE001 - 命令行工具，直接打印错误
        print("[失败] %s" % error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
