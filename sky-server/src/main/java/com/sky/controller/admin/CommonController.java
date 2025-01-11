package com.sky.controller.admin;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSException;
import com.sky.result.Result;
import com.sky.utils.AliOSSUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Slf4j
@RequestMapping("/admin/common")
public class CommonController {
    @Autowired
    public AliOSSUtils aliOSSUtils;

    @PostMapping("/upload")
    public Result upload(MultipartFile file) {
        try {
            log.info("文件上传:{}", file.getOriginalFilename());
            String url = aliOSSUtils.uploadFile(file);
            return Result.success(url);
        } catch (OSSException oe) {
            log.error("OSSException: {}", oe.getMessage(), oe);
            return Result.error("文件上传失败（OSS错误）：" + oe.getMessage());
        } catch (ClientException ce) {
            log.error("ClientException: {}", ce.getMessage(), ce);
            return Result.error("文件上传失败（客户端错误）：" + ce.getMessage());
        } catch (Exception e) {
            log.error("未知异常: {}", e.getMessage(), e);
            return Result.error("文件上传失败（未知错误）：" + e.getMessage());
        }
    }
}
