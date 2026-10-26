import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
public class SetPwd {
    public static void main(String[] a) {
        BCryptPasswordEncoder e = new BCryptPasswordEncoder();
        String h = e.encode("123456");
        System.out.println(h);
        System.out.println("verify=" + e.matches("123456", h));
        System.out.println("verify-old=" + e.matches("123456", "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
    }
}
