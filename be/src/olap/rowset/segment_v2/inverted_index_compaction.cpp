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

#include "inverted_index_compaction.h"

#include <CLucene.h>

#include "inverted_index_compound_directory.h"
#include "inverted_index_compound_reader.h"
#include "util/debug_points.h"

namespace doris::segment_v2 {
Status compact_column(int32_t index_id, int src_segment_num, int dest_segment_num,
                      std::vector<std::string> src_index_files,
                      std::vector<std::string> dest_index_files, const io::FileSystemSPtr& fs,
                      std::string index_writer_path, std::string tablet_path,
                      std::vector<std::vector<std::pair<uint32_t, uint32_t>>> trans_vec,
                      std::vector<uint32_t> dest_segment_num_rows) {
    DBUG_EXECUTE_IF("index_compaction_compact_column_throw_error", {
        if (index_id % 2 == 0) {
            _CLTHROWA(CL_ERR_IO, "debug point: test throw error in index compaction");
        }
    })
    DBUG_EXECUTE_IF("index_compaction_compact_column_status_not_ok", {
        if (index_id % 2 == 1) {
            return Status::Error<ErrorCode::INVERTED_INDEX_COMPACTION_ERROR>(
                    "debug point: index compaction error");
        }
    })
    lucene::store::Directory* dir =
            DorisCompoundDirectoryFactory::getDirectory(fs, index_writer_path.c_str());
    lucene::analysis::SimpleAnalyzer<char> analyzer;
    auto* index_writer = _CLNEW lucene::index::IndexWriter(dir, &analyzer, true /* create */,
                                                           true /* closeDirOnShutdown */);

    // get compound directory src_index_dirs
    std::vector<lucene::store::Directory*> src_index_dirs(src_segment_num);
    for (int i = 0; i < src_segment_num; ++i) {
        // format: rowsetId_segmentId_indexId.idx
        std::string src_idx_full_name =
                src_index_files[i] + "_" + std::to_string(index_id) + ".idx";
        auto* reader = new DorisCompoundReader(
                DorisCompoundDirectoryFactory::getDirectory(fs, tablet_path.c_str()),
                src_idx_full_name.c_str());
        src_index_dirs[i] = reader;
    }

    // get dest idx file paths
    std::vector<lucene::store::Directory*> dest_index_dirs(dest_segment_num);
    for (int i = 0; i < dest_segment_num; ++i) {
        // format: rowsetId_segmentId_columnId
        auto path = tablet_path + "/" + dest_index_files[i] + "_" + std::to_string(index_id);
        dest_index_dirs[i] = DorisCompoundDirectoryFactory::getDirectory(fs, path.c_str(), true);
    }

    DCHECK_EQ(src_index_dirs.size(), trans_vec.size());
    index_writer->indexCompaction(src_index_dirs, dest_index_dirs, trans_vec,
                                  dest_segment_num_rows);

    index_writer->close();
    _CLDELETE(index_writer);
    // NOTE: need to ref_cnt-- for dir,
    // when index_writer is destroyed, if closeDir is set, dir will be close
    // _CLDECDELETE(dir) will try to ref_cnt--, when it decreases to 1, dir will be destroyed.
    _CLDECDELETE(dir)
    for (auto* d : src_index_dirs) {
        if (d != nullptr) {
            d->close();
            _CLDELETE(d);
        }
    }
    for (auto* d : dest_index_dirs) {
        if (d != nullptr) {
            // NOTE: DO NOT close dest dir here, because it will be closed when dest index writer finalize.
            //d->close();
            _CLDELETE(d);
        }
    }

    // delete temporary index_writer_path
    static_cast<void>(fs->delete_directory(index_writer_path.c_str()));
    return Status::OK();
}
} // namespace doris::segment_v2
