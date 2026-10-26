package com.canteen.common;

/**
 * 当前登录用户 ThreadLocal
 */
public final class UserContext {

    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    public static LoginUser get() {
        LoginUser u = HOLDER.get();
        if (u == null) {
            throw new BizException(401, "登录已失效，请重新登录");
        }
        return u;
    }

    public static LoginUser getOrNull() {
        return HOLDER.get();
    }

    public static Long userId() {
        return get().getUserId();
    }

    public static String role() {
        return get().getRole();
    }

    public static Long farmerId() {
        LoginUser u = get();
        if (u.getFarmerId() == null) {
            throw new BizException(403, "当前账号未绑定农户档案");
        }
        return u.getFarmerId();
    }

    public static Long canteenId() {
        LoginUser u = get();
        if (u.getCanteenId() == null) {
            throw new BizException(403, "当前账号未绑定食堂档案");
        }
        return u.getCanteenId();
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(get().getRole());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
