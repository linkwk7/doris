// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include <atomic>
#include <cstdint>
#include <iosfwd>
#include <memory>
#include <utility>
#include <vector>

#include "common/status.h"
#include "olap/memtable.h"
#include "util/threadpool.h"

namespace doris {

class DataDir;
class MemTable;
class RowsetWriter;

// the statistic of a certain flush handler.
// use atomic because it may be updated by multi threads
struct FlushStatistic {
    std::atomic_uint64_t flush_time_ns = 0;
    std::atomic_uint64_t flush_running_count = 0;
    std::atomic_uint64_t flush_finish_count = 0;
    std::atomic_uint64_t flush_size_bytes = 0;
    std::atomic_uint64_t flush_disk_size_bytes = 0;
    std::atomic_uint64_t flush_wait_time_ns = 0;
};

std::ostream& operator<<(std::ostream& os, const FlushStatistic& stat);

// A thin wrapper of ThreadPoolToken to submit task.
// For a tablet, there may be multiple memtables, which will be flushed to disk
// one by one in the order of generation.
// If a memtable flush fails, then:
// 1. Immediately disallow submission of any subsequent memtable
// 2. For the memtables that have already been submitted, there is no need to flush,
//    because the entire job will definitely fail;
class FlushToken {
public:
    explicit FlushToken(std::unique_ptr<ThreadPoolToken> flush_pool_token)
            : _flush_token(std::move(flush_pool_token)), _flush_status(Status::OK()) {}

    Status submit(std::unique_ptr<MemTable> mem_table);

    // error has happens, so we cancel this token
    // And remove all tasks in the queue.
    void cancel();

    // wait all tasks in token to be completed.
    Status wait();

    // get flush operations' statistics
    const FlushStatistic& get_stats() const { return _stats; }

    void set_rowset_writer(RowsetWriter* rowset_writer) { _rowset_writer = rowset_writer; }

    const MemTableStat& memtable_stat() { return _memtable_stat; }

private:
    friend class MemtableFlushTask;

    void _flush_memtable(MemTable* mem_table, int32_t segment_id, int64_t submit_task_time);

    Status _do_flush_memtable(MemTable* memtable, int32_t segment_id, int64_t* flush_size);

    std::unique_ptr<ThreadPoolToken> _flush_token;

    // Records the current flush status of the tablet.
    // Note: Once its value is set to Failed, it cannot return to SUCCESS.
    std::shared_mutex _flush_status_lock;
    Status _flush_status;

    FlushStatistic _stats;

    RowsetWriter* _rowset_writer = nullptr;

    MemTableStat _memtable_stat;
};

// MemTableFlushExecutor is responsible for flushing memtables to disk.
// It encapsulate a ThreadPool to handle all tasks.
// Usage Example:
//      ...
//      std::shared_ptr<FlushHandler> flush_handler;
//      memTableFlushExecutor.create_flush_token(&flush_handler);
//      ...
//      flush_token->submit(memtable)
//      ...
class MemTableFlushExecutor {
public:
    MemTableFlushExecutor() = default;
    ~MemTableFlushExecutor() {
        _deregister_metrics();
        _flush_pool->shutdown();
        _high_prio_flush_pool->shutdown();
    }

    // init should be called after storage engine is opened,
    // because it needs path hash of each data dir.
    void init(const std::vector<DataDir*>& data_dirs);

    Status create_flush_token(std::unique_ptr<FlushToken>& flush_token, RowsetWriter* rowset_writer,
                              bool should_serial, bool is_high_priority);

private:
    void _register_metrics();
    static void _deregister_metrics();

    std::unique_ptr<ThreadPool> _flush_pool;
    std::unique_ptr<ThreadPool> _high_prio_flush_pool;
};

} // namespace doris
