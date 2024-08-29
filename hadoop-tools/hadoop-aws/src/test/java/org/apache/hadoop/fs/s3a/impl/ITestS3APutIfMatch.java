/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.fs.s3a.impl;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSDataOutputStreamBuilder;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.s3a.AbstractS3ATestBase;
import org.apache.hadoop.fs.s3a.RemoteFileChangedException;
import org.apache.hadoop.fs.s3a.S3ATestUtils;
import org.apache.hadoop.io.IOUtils;

import org.junit.Assert;
import org.junit.Test;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import static org.apache.hadoop.fs.contract.ContractTestUtils.dataset;
import static org.apache.hadoop.fs.s3a.Constants.FAST_UPLOAD_BUFFER;
import static org.apache.hadoop.fs.s3a.Constants.FAST_UPLOAD_BUFFER_ARRAY;
import static org.apache.hadoop.fs.s3a.Constants.FS_S3A_CREATE_IF_NONE_MATCH;
import static org.apache.hadoop.fs.s3a.Constants.MIN_MULTIPART_THRESHOLD;
import static org.apache.hadoop.fs.s3a.Constants.MULTIPART_MIN_SIZE;
import static org.apache.hadoop.fs.s3a.Constants.MULTIPART_SIZE;
import static org.apache.hadoop.fs.s3a.S3ATestUtils.removeBaseAndBucketOverrides;
import static org.apache.hadoop.fs.s3a.impl.InternalConstants.UPLOAD_PART_COUNT_LIMIT;
import static org.apache.hadoop.fs.s3a.scale.ITestS3AMultipartUploadSizeLimits.MPU_SIZE;
import static org.apache.hadoop.fs.s3a.scale.S3AScaleTestBase._1MB;
import static org.apache.hadoop.test.LambdaTestUtils.intercept;


public class ITestS3APutIfMatch extends AbstractS3ATestBase {

    @Override
    protected Configuration createConfiguration() {
        Configuration conf = super.createConfiguration();
        S3ATestUtils.disableFilesystemCaching(conf);
        removeBaseAndBucketOverrides(conf,
            MULTIPART_SIZE,
            UPLOAD_PART_COUNT_LIMIT);
        conf.setLong(MULTIPART_SIZE, MPU_SIZE);
        conf.setLong(UPLOAD_PART_COUNT_LIMIT, 2);
        conf.setLong(MIN_MULTIPART_THRESHOLD, MULTIPART_MIN_SIZE);
        conf.setInt(MULTIPART_SIZE, MULTIPART_MIN_SIZE);
        conf.set(FAST_UPLOAD_BUFFER, getBlockOutputBufferName());
        return conf;
    }

    protected String getBlockOutputBufferName() {
        return FAST_UPLOAD_BUFFER_ARRAY;
    }

    /**
     * Create a file using the PutIfMatch feature from S3
     * @param fs filesystem
     * @param path       path to write
     * @param data source dataset. Can be null
     * @throws IOException on any problem
     */
    private static void createFileWithIfNoneMatchFlag(FileSystem fs,
                                                      Path path,
                                                      byte[] data,
                                                      String ifMatchTag) throws Exception {
          FSDataOutputStreamBuilder builder = fs.createFile(path);
          builder.must(FS_S3A_CREATE_IF_NONE_MATCH, ifMatchTag);
          FSDataOutputStream stream = builder.create().build();
          if (data != null && data.length > 0) {
              stream.write(data);
          }
          stream.close();
          IOUtils.closeStream(stream);
    }

    @Test
    public void testPutIfAbsentConflict() throws Exception {
        FileSystem fs = getFileSystem();
        Path testFile = methodPath();

        fs.mkdirs(testFile.getParent());
        byte[] fileBytes = dataset(TEST_FILE_LEN, 0, 255);

        try {
          createFileWithIfNoneMatchFlag(fs, testFile, fileBytes, "*");
          createFileWithIfNoneMatchFlag(fs, testFile, fileBytes, "*");
        } catch (Exception e) {

          intercept(RemoteFileChangedException.class, e::getClass);

          S3Exception s3Exception = (S3Exception) e.getCause();
          Assert.assertEquals(s3Exception.statusCode(), 412);
        }
    }


    @Test
    public void testPutIfAbsentLargeFileConflict() throws IOException {
        FileSystem fs = getFileSystem();
        Path testFile = methodPath();

        fs.mkdirs(testFile.getParent());
        // enough bytes for Multipart Upload
        byte[] fileBytes = dataset(6 * _1MB, 'a', 'z' - 'a');

        try {
            createFileWithIfNoneMatchFlag(fs, testFile, fileBytes, "*");
            createFileWithIfNoneMatchFlag(fs, testFile, fileBytes, "*");
        } catch (Exception e) {
          Assert.assertEquals(RemoteFileChangedException.class, e.getClass());

          // Error gets caught here:
          S3Exception s3Exception = (S3Exception) e.getCause();
          Assert.assertEquals(s3Exception.statusCode(), 412);
        }
    }
}
