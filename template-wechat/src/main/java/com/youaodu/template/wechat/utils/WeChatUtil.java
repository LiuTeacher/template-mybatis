package com.youaodu.template.wechat.utils;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.youaodu.template.common.framework.exception.BusinessException;
import com.youaodu.template.common.framework.utils.RedisUtils;
import com.youaodu.template.common.framework.utils.SpringUtils;
import com.youaodu.template.wechat.bo.*;
import com.youaodu.template.wechat.config.WeChatConfig;
import com.youaodu.template.wechat.config.WeChatUrls;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class WeChatUtil {

    private static RedisUtils redisUtils = SpringUtils.getBean(RedisUtils.class);

    private static WeChatConfig weChatConfig = SpringUtils.getBean(WeChatConfig.class);

    private static final String accesskeyName = "weChat_accessToken";

    private static final String jsapiticketName = "weChat_jsapiTicket";

    private static String accessToken() {
        return redisUtils.getStr(accesskeyName);
    }

    private static String jsapiTicket() {
        return redisUtils.getStr(jsapiticketName);
    }
    /**
     * 刷新AccessToken
     */
    public static void reloadAccessToken() {
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("grant_type", "client_credential");
        requestParams.put("appid", weChatConfig.getAppId());
        requestParams.put("secret", weChatConfig.getAppSecret());

        // 发送请求
        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.accessToken, requestParams));
        log.debug("accessToken返回:{}",response.toString());
        String accessToken = response.getStr("access_token");
        if (StrUtil.isNotBlank(accessToken)) {
            // token存储redis
            redisUtils.set(accesskeyName, accessToken, 3600L);
            log.info("accessToken loading success");
            loadJsapiTicket();
        } else {
            log.error("accessToken loading error: {}", response.toString());
        }
    }

    public static void loadJsapiTicket() {
        Map<String, Object> params = new HashMap<>();
        params.put("access_token", accessToken());
        params.put("type", "jsapi");
        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.jsapiTicket, params));
        String ticket = response.getStr("ticket");
        redisUtils.set(jsapiticketName, ticket, 3600L);
        log.info("jsapiticket loading success");
    }

    /**
     * 设置buttons
     * @param menuBos
     */
    public static boolean settingButtons(List<MenuBo> menuBos) {
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("button", boToMenu(menuBos));

        // 发起请求
        HttpRequest post = HttpUtil.createPost(WeChatUrls.settingButtons + "?access_token=" + accessToken());
        post.body(JSONUtil.toJsonStr(requestParams));
        JSONObject response = JSONUtil.parseObj(post.execute().body());

        if (StrUtil.equals(response.getStr("errcode"), "0") && StrUtil.equals(response.getStr("errmsg"), "ok")) {
            return true;
        } else {
            log.error("设置失败 -> {}", response.toString());
            return false;
        }
    }

    /**
     * 当前菜单按钮集
     * @return
     */
    public static List<MenuBo> currentButtons() {
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("access_token", accessToken());

        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.showButtons, requestParams));
        JSONObject buttonsObj = response.getJSONObject("selfmenu_info");

        if (buttonsObj != null)
            return menuToBo(buttonsObj);
        else
            return null;
    }

    /**
     * 前端JS鉴权
     * @param scriptAuthBo
     * @return
     */
    public static ScriptAuthBoVo scriptAuth(ScriptAuthBo scriptAuthBo) {
        // 拼接获取签名桉树
        TreeMap<String, Object> tmpParams = new TreeMap<>();
        String randomStr = IdUtil.fastUUID();
        tmpParams.put("noncestr", randomStr);
        tmpParams.put("jsapi_ticket", jsapiTicket());
        long time = System.currentTimeMillis() / 1000;
        tmpParams.put("timestamp", time);
        tmpParams.put("url", scriptAuthBo.getCurrUrl());

        String sign = genSign(tmpParams);
        // 拼装结果集
        ScriptAuthBoVo result = new ScriptAuthBoVo();
        result.setAppId(weChatConfig.getAppId());
        result.setNonceStr(randomStr);
        result.setTimestamp(time);
        result.setSignature(sign);
        return result;
    }

    /**
     * 根据code获取用户信息
     * @param code
     * @return
     */
    public static QueryOpenIdByCodeBoVo queryOpenIdByCode(String code) {
        // 拼接参数
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("appid", weChatConfig.getAppId());
        params.put("secret", weChatConfig.getAppSecret());
        params.put("code", code);
        params.put("grant_type", "authorization_code");

        // 发起请求
        log.info("获取openID 请求入参 {}", JSONUtil.toJsonStr(params));
        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.openId, params));
        log.info("获取openID 请求出参 {}", response.toString());

        if (StrUtil.isNotBlank(response.getStr("access_token"))) {
            // 拼接返回
            QueryOpenIdByCodeBoVo result = new QueryOpenIdByCodeBoVo();
            result.setAccessToken(response.getStr("access_token"));
            result.setOpenId(response.getStr("openid"));
            return result;
        }
        throw new BusinessException("获取openId失败");
    }

    /**
     * 根据openId获取用户信息
     * @param openId 公众号唯一ID
     * @param accessToken 授权token
     * @return
     */
    public static QueryUserInfoByOpenIdBoVo queryUserInfoByOpenId(String openId, String accessToken) {
        // 请求参数
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("access_token", accessToken);
        params.put("openid", openId);
        params.put("lang", "zh_CN");

        // 发起请求
        log.info("根据openId获取用户信息 请求入参 {}", JSONUtil.toJsonStr(params));
        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.userInfo, params));
        log.info("根据openId获取用户信息 请求出参 {}", response.toString());

        return response.toBean(QueryUserInfoByOpenIdBoVo.class);
    }

    /**
     * 查询用户列表
     * @param nextId
     * @return
     */
    public static QueryUserListBoVo queryUserList(String nextId) {
        // 请求参数
        LinkedHashMap<String, Object> params = new LinkedHashMap<>();
        params.put("access_token", accessToken());
        params.put("next_openid", nextId);

        // 发起请求
        log.info("查询用户列表 请求入参 {}", JSONUtil.toJsonStr(params));
        JSONObject response = JSONUtil.parseObj(HttpUtil.get(WeChatUrls.userList, params));
        log.info("根据openId获取用户信息 请求出参 {}", response.toString());

        QueryUserListBoVo result = new QueryUserListBoVo();
        result.setCurrTotal(response.getInt("count"));
        result.setOpenIds(response.getJSONObject("data").getJSONArray("openid").toList(String.class));
        result.setTotal(response.getInt("total"));
        return result;
    }



    private static String genSign(TreeMap<String, Object> treeMap) {
        String signStr = HttpUtil.toParams(treeMap);
        try {
            signStr = URLDecoder.decode(signStr, "utf-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return SecureUtil.sha1(signStr);
    }

    private static List<Map<String, Object>> boToMenu(List<MenuBo> menus) {
        List<Map<String, Object>> result = new ArrayList<>();

        menus.forEach(it -> {
            // 转换按钮集
            HashMap<String, Object> resultItem = new HashMap<>();
            resultItem.put("type", it.getType());
            resultItem.put("name", it.getName());
            resultItem.put("key", it.getKey());
            resultItem.put("url", it.getUrl());

            if (CollUtil.isNotEmpty(it.getChildren())) {
                // 递归下级
                resultItem.put("sub_button", boToMenu(it.getChildren()));
            }
            result.add(resultItem);
        });
        return result;
    }

    /**
     * 微信返回的JSON转BO
     * @param buts
     * @return
     */
    private static List<MenuBo> menuToBo(JSONObject buts) {
        JSONArray tmpArray;

        // 解析JSONArray
        if (buts.getJSONArray("button") != null)
            tmpArray = buts.getJSONArray("button");
        else if (buts.getJSONArray("list") != null)
            tmpArray = buts.getJSONArray("list");
        else
            return null;

        return tmpArray.stream().map(it -> {
            JSONObject item = JSONUtil.parseObj(it.toString());
            MenuBo resultItem = new MenuBo();

            resultItem.setName(item.getStr("name"));
            resultItem.setType(item.getStr("type"));
            resultItem.setUrl(item.getStr("url"));
            resultItem.setKey(item.getStr("key"));

            if (item.getStr("sub_button") != null) {
                // 递归
                resultItem.setChildren(menuToBo(item.getJSONObject("sub_button")));
            }

            return resultItem;
        }).collect(Collectors.toList());
    }
}
