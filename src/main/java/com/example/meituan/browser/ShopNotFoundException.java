package com.example.meituan.browser;

import java.io.IOException;

/**
 * 指定店铺在美团后台未找到：切换失败，或切换后二次校验的店铺名与目标不一致。
 * 调用方应中止当前抓取任务并向用户提示，而不是继续抓取当前页面所属店铺。
 */
public class ShopNotFoundException extends IOException {

    public ShopNotFoundException(String message) {
        super(message);
    }
}
