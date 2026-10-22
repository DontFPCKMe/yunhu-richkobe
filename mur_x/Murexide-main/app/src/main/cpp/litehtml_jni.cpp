#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "litehtml.h"
#include "litehtml/el_text.h"
#include "litehtml/render_item.h"

namespace {

constexpr const char* kLogTag = "MurexideLiteHtml";
constexpr float kMaxDocumentHeightCssPx = 250000.0F;
constexpr const char* kDetailsContentClass = "murexide-details-content";

void log_error(const char* message) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, message);
}

std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    std::string result;
    result.reserve(static_cast<size_t>(length) * 3U);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800U && codepoint <= 0xDBFFU && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00U && low <= 0xDFFFU) {
                codepoint = 0x10000U + ((codepoint - 0xD800U) << 10U) + (low - 0xDC00U);
                ++i;
            } else {
                codepoint = 0xFFFDU;
            }
        } else if (codepoint >= 0xD800U && codepoint <= 0xDFFFU) {
            codepoint = 0xFFFDU;
        }
        if (codepoint <= 0x7FU) {
            result.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FFU) {
            result.push_back(static_cast<char>(0xC0U | (codepoint >> 6U)));
            result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
        } else if (codepoint <= 0xFFFFU) {
            result.push_back(static_cast<char>(0xE0U | (codepoint >> 12U)));
            result.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
        } else {
            result.push_back(static_cast<char>(0xF0U | (codepoint >> 18U)));
            result.push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3FU)));
            result.push_back(static_cast<char>(0x80U | (codepoint & 0x3FU)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv* env, const std::string& value) {
    std::vector<jchar> result;
    result.reserve(value.size());
    size_t i = 0;
    while (i < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[i++]);
        uint32_t codepoint = 0xFFFDU;
        int continuation = 0;
        if (first <= 0x7FU) {
            codepoint = first;
        } else if ((first & 0xE0U) == 0xC0U) {
            codepoint = first & 0x1FU;
            continuation = 1;
        } else if ((first & 0xF0U) == 0xE0U) {
            codepoint = first & 0x0FU;
            continuation = 2;
        } else if ((first & 0xF8U) == 0xF0U) {
            codepoint = first & 0x07U;
            continuation = 3;
        }
        bool valid = true;
        for (int n = 0; n < continuation; ++n) {
            if (i >= value.size()) {
                valid = false;
                break;
            }
            const uint8_t next = static_cast<uint8_t>(value[i]);
            if ((next & 0xC0U) != 0x80U) {
                valid = false;
                break;
            }
            ++i;
            codepoint = (codepoint << 6U) | (next & 0x3FU);
        }
        if (!valid || codepoint > 0x10FFFFU || (codepoint >= 0xD800U && codepoint <= 0xDFFFU)) {
            codepoint = 0xFFFDU;
        }
        if (codepoint <= 0xFFFFU) {
            result.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000U;
            result.push_back(static_cast<jchar>(0xD800U + (codepoint >> 10U)));
            result.push_back(static_cast<jchar>(0xDC00U + (codepoint & 0x3FFU)));
        }
    }
    return env->NewString(result.data(), static_cast<jsize>(result.size()));
}

jint to_android_color(const litehtml::web_color& color) {
    return static_cast<jint>((static_cast<uint32_t>(color.alpha) << 24U) |
                             (static_cast<uint32_t>(color.red) << 16U) |
                             (static_cast<uint32_t>(color.green) << 8U) |
                             static_cast<uint32_t>(color.blue));
}

float px(litehtml::pixel_t value) {
    return value.value();
}

class DisclosureMarkerText final : public litehtml::el_text {
public:
    explicit DisclosureMarkerText(const std::shared_ptr<litehtml::document>& document)
        : el_text("", document) {}

    void set_open(bool open, bool recompute) {
        m_text = open ? "\xE2\x96\xBC\xC2\xA0" : "\xE2\x96\xB6\xC2\xA0";
        if (recompute) compute_styles(false);
    }
};

class DetailsElement final : public litehtml::html_tag {
public:
    explicit DetailsElement(const std::shared_ptr<litehtml::document>& document)
        : html_tag(document) {}

    void set_attr(const char* name, const char* value) override {
        if (name != nullptr && std::string(name) == "open") {
            open_ = true;
        }
        html_tag::set_attr(name, value);
    }

    const char* get_attr(const char* name, const char* default_value = nullptr) const override {
        if (name != nullptr && std::string(name) == "open") {
            return open_ ? "" : default_value;
        }
        return html_tag::get_attr(name, default_value);
    }

    void parse_attributes() override {
        if (!initialized_) {
            initialized_ = true;
            initialize_disclosure();
        }
        html_tag::parse_attributes();
    }

    bool owns_summary(const litehtml::element::ptr& element) const {
        return summary_ != nullptr && summary_ == element;
    }

    bool toggle() {
        if (summary_ == nullptr) return false;
        open_ = !open_;
        apply_state(true);
        return true;
    }

private:
    void initialize_disclosure() {
        for (const auto& child : m_children) {
            if (child != nullptr && std::string(child->get_tagName()) == "summary") {
                summary_ = child;
                break;
            }
        }
        // Invalid details without a direct summary stay expanded so their content is reachable.
        if (summary_ == nullptr) return;

        content_ = std::make_shared<litehtml::html_tag>(get_document());
        content_->set_tagName("div");
        content_->set_attr("class", kDetailsContentClass);

        auto summary_tag = std::dynamic_pointer_cast<litehtml::html_tag>(summary_);
        if (summary_tag == nullptr) return;
        marker_ = std::make_shared<DisclosureMarkerText>(get_document());
        marker_->parent(summary_);
        summary_tag->children().insert(summary_tag->children().begin(), marker_);

        auto original_children = m_children;
        m_children.clear();
        appendChild(summary_);
        for (const auto& child : original_children) {
            if (child != summary_) content_->appendChild(child);
        }
        appendChild(content_);
        apply_state(false);
    }

    void apply_state(bool recompute) {
        content_->set_attr(
            "style",
            open_ ? "display:block;height:auto;overflow:hidden"
                  : "display:block;height:0;overflow:hidden");
        marker_->set_open(open_, recompute);
        if (recompute) {
            content_->refresh_styles();
            content_->compute_styles();
            // litehtml cannot discover selectors that did not match during initial parsing.
            content_->css_w().set_height(
                open_ ? litehtml::css_length::predef_value() : litehtml::css_length(0));
        }
    }

    bool initialized_ = false;
    bool open_ = false;
    litehtml::element::ptr summary_;
    litehtml::element::ptr content_;
    std::shared_ptr<DisclosureMarkerText> marker_;
};

class AndroidDocumentContainer final : public litehtml::document_container {
public:
    AndroidDocumentContainer(JNIEnv* env, jobject view, float density, int default_font_size)
        : density_(std::max(density, 0.1F)),
          default_font_size_(std::max(default_font_size, 8)) {
        env->GetJavaVM(&vm_);
        view_ = env->NewGlobalRef(view);
        jclass local_class = env->GetObjectClass(view);
        view_class_ = static_cast<jclass>(env->NewGlobalRef(local_class));
        env->DeleteLocalRef(local_class);

        create_font_method_ = method(env, "createFontFromNative", "(Ljava/lang/String;FIZI)I");
        delete_font_method_ = method(env, "deleteFontFromNative", "(I)V");
        font_metrics_method_ = method(env, "fontMetricsFromNative", "(I)[F");
        text_width_method_ = method(env, "textWidthFromNative", "(ILjava/lang/String;)F");
        draw_text_method_ = method(env, "drawTextFromNative",
                                   "(Landroid/graphics/Canvas;ILjava/lang/String;IFFF)V");
        draw_rect_method_ = method(env, "drawRectFromNative", "(Landroid/graphics/Canvas;IFFFFFF)V");
        draw_image_method_ = method(env, "drawImageFromNative",
                                    "(Landroid/graphics/Canvas;Ljava/lang/String;FFFF)V");
        draw_gradient_method_ = method(env, "drawGradientFromNative",
                                       "(Landroid/graphics/Canvas;IFFFFFFFF[I[F)V");
        save_clip_method_ = method(env, "saveClipFromNative", "(Landroid/graphics/Canvas;FFFFFF)V");
        restore_clip_method_ = method(env, "restoreClipFromNative", "(Landroid/graphics/Canvas;)V");
        request_image_method_ = method(env, "requestImageFromNative", "(Ljava/lang/String;)V");
        dispatch_link_method_ = method(env, "dispatchLinkFromNative", "(Ljava/lang/String;)V");
        dispatch_image_method_ = method(env, "dispatchImageFromNative", "(Ljava/lang/String;)V");
    }

    ~AndroidDocumentContainer() override {
        JNIEnv* env = get_env();
        // litehtml releases its font handles from document destruction, so the Java peer must
        // remain valid until the document has been torn down.
        document_.reset();
        if (env != nullptr) {
            if (view_ != nullptr) env->DeleteGlobalRef(view_);
            if (view_class_ != nullptr) env->DeleteGlobalRef(view_class_);
        }
    }

    bool create_document(const std::string& html, const std::string& css) {
        document_ = litehtml::document::createFromString(html.c_str(), this, litehtml::master_css, css);
        return document_ != nullptr;
    }

    int layout(int width) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (!document_) return 0;
        viewport_width_ = std::max(width, 1);
        document_->render(viewport_width_);
        const float raw_height = px(document_->height());
        const float safe_height = std::isfinite(raw_height)
            ? std::clamp(raw_height, 1.0F, kMaxDocumentHeightCssPx)
            : 1.0F;
        document_height_ = static_cast<int>(std::ceil(safe_height));
        return document_height_;
    }

    void draw(jobject canvas, float tile_top, float tile_height) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (!document_ || canvas == nullptr) return;
        litehtml::position clip(0.0F, tile_top, static_cast<float>(viewport_width_), tile_height);
        document_->draw(reinterpret_cast<litehtml::uint_ptr>(canvas), 0, 0, &clip);
    }

    void set_image_size(const std::string& url, int width_pixels, int height_pixels) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (url.empty() || width_pixels <= 0 || height_pixels <= 0) return;
        image_sizes_[url] = {
            std::max(1, static_cast<int>(std::round(width_pixels / density_))),
            std::max(1, static_cast<int>(std::round(height_pixels / density_)))
        };
    }

    int hit_test(float x, float y) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        return find_hit_target(x, y).type;
    }

    void pointer_down(float x, float y) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (!document_) return;
        const auto target = find_hit_target(x, y);
        active_details_ = target.details;
        document_->on_lbutton_down(x, y, x, y, [](const litehtml::position&) {});
    }

    bool pointer_up(float x, float y) {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (!document_) return false;
        const auto target = find_hit_target(x, y);
        document_->on_lbutton_up(x, y, x, y, [](const litehtml::position&) {});
        const bool details_state_changed = target.details != nullptr &&
            target.details == active_details_ && target.details->toggle();
        active_details_.reset();
        return details_state_changed;
    }

    void pointer_cancel() {
        std::lock_guard<std::recursive_mutex> guard(mutex_);
        if (!document_) return;
        active_details_.reset();
        document_->on_button_cancel([](const litehtml::position&) {});
    }

    litehtml::uint_ptr create_font(const litehtml::font_description& descr,
                                   const litehtml::document*, litehtml::font_metrics* metrics) override {
        JNIEnv* env = get_env();
        if (env == nullptr || create_font_method_ == nullptr) return 0;
        jstring family = to_jstring(env, descr.family);
        const jint id = env->CallIntMethod(
            view_, create_font_method_, family, px(descr.size), static_cast<jint>(descr.weight),
            static_cast<jboolean>(descr.style == litehtml::font_style_italic),
            static_cast<jint>(descr.decoration_line));
        env->DeleteLocalRef(family);
        clear_exception(env);

        if (metrics != nullptr && id > 0 && font_metrics_method_ != nullptr) {
            auto values = static_cast<jfloatArray>(env->CallObjectMethod(view_, font_metrics_method_, id));
            if (!env->ExceptionCheck() && values != nullptr && env->GetArrayLength(values) >= 8) {
                jfloat raw[8]{};
                env->GetFloatArrayRegion(values, 0, 8, raw);
                metrics->font_size = raw[0];
                metrics->height = raw[1];
                metrics->ascent = raw[2];
                metrics->descent = raw[3];
                metrics->x_height = raw[4];
                metrics->ch_width = raw[5];
                metrics->sub_shift = raw[6];
                metrics->super_shift = raw[7];
                metrics->draw_spaces = descr.decoration_line != litehtml::text_decoration_line_none;
            }
            if (values != nullptr) env->DeleteLocalRef(values);
            clear_exception(env);
        }
        return static_cast<litehtml::uint_ptr>(std::max(id, 0));
    }

    void delete_font(litehtml::uint_ptr font) override {
        JNIEnv* env = get_env();
        if (env == nullptr || delete_font_method_ == nullptr || font == 0) return;
        env->CallVoidMethod(view_, delete_font_method_, static_cast<jint>(font));
        clear_exception(env);
    }

    litehtml::pixel_t text_width(const char* text, litehtml::uint_ptr font) override {
        JNIEnv* env = get_env();
        if (env == nullptr || text_width_method_ == nullptr || text == nullptr) return 0;
        jstring value = to_jstring(env, text);
        const float width = env->CallFloatMethod(view_, text_width_method_, static_cast<jint>(font), value);
        env->DeleteLocalRef(value);
        clear_exception(env);
        return std::max(width, 0.0F);
    }

    void draw_text(litehtml::uint_ptr hdc, const char* text, litehtml::uint_ptr font,
                   litehtml::web_color color, const litehtml::position& pos) override {
        JNIEnv* env = get_env();
        if (env == nullptr || draw_text_method_ == nullptr || text == nullptr) return;
        auto canvas = reinterpret_cast<jobject>(hdc);
        jstring value = to_jstring(env, text);
        env->CallVoidMethod(view_, draw_text_method_, canvas, static_cast<jint>(font), value,
                            to_android_color(color), px(pos.x), px(pos.y), px(pos.width));
        env->DeleteLocalRef(value);
        clear_exception(env);
    }

    litehtml::pixel_t pt_to_px(float pt) const override {
        return pt * 96.0F / 72.0F;
    }

    litehtml::pixel_t get_default_font_size() const override {
        return default_font_size_;
    }

    const char* get_default_font_name() const override {
        return "sans-serif";
    }

    void draw_list_marker(litehtml::uint_ptr hdc, const litehtml::list_marker& marker) override {
        std::string text;
        switch (marker.marker_type) {
            case litehtml::list_style_type_circle: text = "\xE2\x97\xA6"; break;
            case litehtml::list_style_type_square: text = "\xE2\x96\xAA"; break;
            case litehtml::list_style_type_disc: text = "\xE2\x80\xA2"; break;
            case litehtml::list_style_type_none: return;
            default: text = std::to_string(marker.index) + "."; break;
        }
        draw_text(hdc, text.c_str(), marker.font, marker.color, marker.pos);
    }

    void load_image(const char* src, const char* base_url, bool) override {
        const std::string url = resolve_resource(src, base_url);
        if (url.empty()) return;
        JNIEnv* env = get_env();
        if (env == nullptr || request_image_method_ == nullptr) return;
        jstring value = to_jstring(env, url);
        env->CallVoidMethod(view_, request_image_method_, value);
        env->DeleteLocalRef(value);
        clear_exception(env);
    }

    void get_image_size(const char* src, const char* base_url, litehtml::size& size) override {
        const std::string url = resolve_resource(src, base_url);
        const auto found = image_sizes_.find(url);
        if (found == image_sizes_.end()) {
            size.width = 64;
            size.height = 48;
            return;
        }
        size.width = found->second.first;
        size.height = found->second.second;
    }

    void draw_image(litehtml::uint_ptr hdc, const litehtml::background_layer& layer,
                    const std::string& url, const std::string& base_url) override {
        const std::string resolved = resolve_resource(url.c_str(), base_url.c_str());
        if (resolved.empty()) return;
        JNIEnv* env = get_env();
        if (env == nullptr || draw_image_method_ == nullptr) return;
        jstring value = to_jstring(env, resolved);
        const auto& box = layer.clip_box;
        env->CallVoidMethod(view_, draw_image_method_, reinterpret_cast<jobject>(hdc), value,
                            px(box.left()), px(box.top()), px(box.right()), px(box.bottom()));
        env->DeleteLocalRef(value);
        clear_exception(env);
    }

    void draw_solid_fill(litehtml::uint_ptr hdc, const litehtml::background_layer& layer,
                         const litehtml::web_color& color) override {
        const auto& box = layer.border_box;
        const float radius_x = std::max({px(layer.border_radius.top_left_x),
                                         px(layer.border_radius.top_right_x),
                                         px(layer.border_radius.bottom_left_x),
                                         px(layer.border_radius.bottom_right_x)});
        const float radius_y = std::max({px(layer.border_radius.top_left_y),
                                         px(layer.border_radius.top_right_y),
                                         px(layer.border_radius.bottom_left_y),
                                         px(layer.border_radius.bottom_right_y)});
        call_draw_rect(hdc, color, box.left(), box.top(), box.right(), box.bottom(), radius_x, radius_y);
    }

    void draw_linear_gradient(litehtml::uint_ptr hdc, const litehtml::background_layer& layer,
                              const litehtml::background_layer::linear_gradient& gradient) override {
        call_draw_gradient(hdc, 0, layer, gradient.start.x, gradient.start.y,
                           gradient.end.x, gradient.end.y, gradient.color_points);
    }

    void draw_radial_gradient(litehtml::uint_ptr hdc, const litehtml::background_layer& layer,
                              const litehtml::background_layer::radial_gradient& gradient) override {
        call_draw_gradient(hdc, 1, layer, gradient.position.x, gradient.position.y,
                           std::max(gradient.radius.x, gradient.radius.y), 0.0F, gradient.color_points);
    }

    void draw_conic_gradient(litehtml::uint_ptr hdc, const litehtml::background_layer& layer,
                             const litehtml::background_layer::conic_gradient& gradient) override {
        call_draw_gradient(hdc, 2, layer, gradient.position.x, gradient.position.y,
                           gradient.angle, 0.0F, gradient.color_points);
    }

    void draw_borders(litehtml::uint_ptr hdc, const litehtml::borders& borders,
                      const litehtml::position& pos, bool) override {
        const float left = px(pos.left());
        const float top = px(pos.top());
        const float right = px(pos.right());
        const float bottom = px(pos.bottom());
        if (borders.top.width.value() > 0.0F && borders.top.style > litehtml::border_style_hidden) {
            call_draw_rect(hdc, borders.top.color, left, top, right, top + px(borders.top.width), 0, 0);
        }
        if (borders.bottom.width.value() > 0.0F && borders.bottom.style > litehtml::border_style_hidden) {
            call_draw_rect(hdc, borders.bottom.color, left, bottom - px(borders.bottom.width), right, bottom, 0, 0);
        }
        if (borders.left.width.value() > 0.0F && borders.left.style > litehtml::border_style_hidden) {
            call_draw_rect(hdc, borders.left.color, left, top, left + px(borders.left.width), bottom, 0, 0);
        }
        if (borders.right.width.value() > 0.0F && borders.right.style > litehtml::border_style_hidden) {
            call_draw_rect(hdc, borders.right.color, right - px(borders.right.width), top, right, bottom, 0, 0);
        }
    }

    void set_caption(const char*) override {}

    void set_base_url(const char* base_url) override {
        base_url_ = base_url == nullptr ? "" : base_url;
    }

    void link(const std::shared_ptr<litehtml::document>&, const litehtml::element::ptr&) override {
        // External stylesheets are intentionally disabled for deterministic message rendering.
    }

    void on_anchor_click(const char* url, const litehtml::element::ptr&) override {
        dispatch_string(dispatch_link_method_, url);
    }

    bool on_element_click(const litehtml::element::ptr& element) override {
        if (!element) return false;
        const std::string tag = element->get_tagName();
        if (tag == "img") {
            const char* src = element->get_attr("src");
            if (src == nullptr) return false;
            const std::string resolved = resolve_resource(src, nullptr);
            if (resolved.empty()) return true;
            dispatch_string(dispatch_image_method_, resolved.c_str());
            return true;
        }
        return false;
    }

    void on_mouse_event(const litehtml::element::ptr&, litehtml::mouse_event) override {}
    void set_cursor(const char*) override {}

    void transform_text(std::string& text, litehtml::text_transform transform) override {
        if (transform == litehtml::text_transform_none) return;
        bool capitalize_next = true;
        for (char& value : text) {
            const unsigned char ch = static_cast<unsigned char>(value);
            if (transform == litehtml::text_transform_uppercase ||
                (transform == litehtml::text_transform_capitalize && capitalize_next)) {
                value = static_cast<char>(std::toupper(ch));
            } else if (transform == litehtml::text_transform_lowercase) {
                value = static_cast<char>(std::tolower(ch));
            }
            capitalize_next = std::isspace(ch) != 0;
        }
    }

    void import_css(std::string& text, const std::string&, std::string&) override {
        text.clear();
    }

    void set_clip(const litehtml::position& pos, const litehtml::border_radiuses& radius) override {
        JNIEnv* env = get_env();
        if (env == nullptr || save_clip_method_ == nullptr || active_canvas_ == nullptr) return;
        const float radius_x = std::max({px(radius.top_left_x), px(radius.top_right_x),
                                         px(radius.bottom_left_x), px(radius.bottom_right_x)});
        const float radius_y = std::max({px(radius.top_left_y), px(radius.top_right_y),
                                         px(radius.bottom_left_y), px(radius.bottom_right_y)});
        env->CallVoidMethod(view_, save_clip_method_, active_canvas_, px(pos.left()), px(pos.top()),
                            px(pos.right()), px(pos.bottom()), radius_x, radius_y);
        clear_exception(env);
    }

    void del_clip() override {
        JNIEnv* env = get_env();
        if (env == nullptr || restore_clip_method_ == nullptr || active_canvas_ == nullptr) return;
        env->CallVoidMethod(view_, restore_clip_method_, active_canvas_);
        clear_exception(env);
    }

    void get_viewport(litehtml::position& viewport) const override {
        viewport = litehtml::position(0, 0, viewport_width_, std::max(document_height_, 1000));
    }

    litehtml::element::ptr create_element(
        const char* tag,
        const litehtml::string_map&,
        const std::shared_ptr<litehtml::document>& document) override {
        if (tag != nullptr && std::string(tag) == "details") {
            return std::make_shared<DetailsElement>(document);
        }
        return nullptr;
    }

    void get_media_features(litehtml::media_features& media) const override {
        media.type = litehtml::media_type_screen;
        media.width = viewport_width_;
        media.height = std::max(document_height_, 1000);
        media.device_width = viewport_width_;
        media.device_height = std::max(document_height_, 1000);
        media.color = 8;
        media.color_index = 0;
        media.monochrome = 0;
        media.resolution = 96.0F * density_;
    }

    void get_language(std::string& language, std::string& culture) const override {
        language = "zh";
        culture = "zh-CN";
    }

    void begin_draw(jobject canvas) { active_canvas_ = canvas; }
    void end_draw() { active_canvas_ = nullptr; }

private:
    struct HitTarget {
        int type = 0;
        std::shared_ptr<DetailsElement> details;
    };

    HitTarget find_hit_target(float x, float y) const {
        if (!document_ || !document_->root_render()) return {};
        auto element = document_->root_render()->get_element_by_point(x, y, x, y, nullptr);
        while (element) {
            const std::string tag = element->get_tagName();
            if (tag == "img" && element->get_attr("src")) return {1, nullptr};
            if (tag == "a" && element->get_attr("href")) return {2, nullptr};
            if (tag == "summary") {
                auto details = std::dynamic_pointer_cast<DetailsElement>(element->parent());
                if (details != nullptr && details->owns_summary(element)) return {3, details};
            }
            element = element->parent();
        }
        return {};
    }

    jmethodID method(JNIEnv* env, const char* name, const char* signature) {
        jmethodID result = env->GetMethodID(view_class_, name, signature);
        if (result == nullptr) clear_exception(env);
        return result;
    }

    JNIEnv* get_env() const {
        if (vm_ == nullptr) return nullptr;
        JNIEnv* env = nullptr;
        const jint status = vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (status == JNI_OK) return env;
        if (status == JNI_EDETACHED && vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) return env;
        return nullptr;
    }

    static void clear_exception(JNIEnv* env) {
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    std::string resolve_resource(const char* source, const char* base_url) const {
        if (source == nullptr) return {};
        std::string value(source);
        if (value.rfind("https://", 0) == 0 || value.rfind("http://", 0) == 0 ||
            value.rfind("data:image/", 0) == 0) {
            return value;
        }
        if (value.rfind("//", 0) == 0) return "https:" + value;
        // Message bodies have no trusted base URL. Relative resources are deliberately rejected.
        (void)base_url;
        return {};
    }

    void dispatch_string(jmethodID method_id, const char* value) {
        if (method_id == nullptr || value == nullptr) return;
        JNIEnv* env = get_env();
        if (env == nullptr) return;
        jstring text = to_jstring(env, value);
        env->CallVoidMethod(view_, method_id, text);
        env->DeleteLocalRef(text);
        clear_exception(env);
    }

    void call_draw_rect(litehtml::uint_ptr hdc, const litehtml::web_color& color,
                        litehtml::pixel_t left, litehtml::pixel_t top,
                        litehtml::pixel_t right, litehtml::pixel_t bottom,
                        float radius_x, float radius_y) {
        call_draw_rect(hdc, color, px(left), px(top), px(right), px(bottom), radius_x, radius_y);
    }

    void call_draw_rect(litehtml::uint_ptr hdc, const litehtml::web_color& color,
                        float left, float top, float right, float bottom,
                        float radius_x, float radius_y) {
        if (color.alpha == 0 || right <= left || bottom <= top) return;
        JNIEnv* env = get_env();
        if (env == nullptr || draw_rect_method_ == nullptr) return;
        env->CallVoidMethod(view_, draw_rect_method_, reinterpret_cast<jobject>(hdc),
                            to_android_color(color), left, top, right, bottom, radius_x, radius_y);
        clear_exception(env);
    }

    void call_draw_gradient(litehtml::uint_ptr hdc, int type, const litehtml::background_layer& layer,
                            float p1, float p2, float p3, float p4,
                            const std::vector<litehtml::background_layer::color_point>& points) {
        if (points.empty()) return;
        JNIEnv* env = get_env();
        if (env == nullptr || draw_gradient_method_ == nullptr) return;
        std::vector<jint> colors;
        std::vector<jfloat> offsets;
        colors.reserve(points.size());
        offsets.reserve(points.size());
        for (const auto& point : points) {
            colors.push_back(to_android_color(point.color));
            offsets.push_back(std::clamp(point.offset, 0.0F, 1.0F));
        }
        jintArray color_array = env->NewIntArray(static_cast<jsize>(colors.size()));
        jfloatArray offset_array = env->NewFloatArray(static_cast<jsize>(offsets.size()));
        env->SetIntArrayRegion(color_array, 0, static_cast<jsize>(colors.size()), colors.data());
        env->SetFloatArrayRegion(offset_array, 0, static_cast<jsize>(offsets.size()), offsets.data());
        const auto& box = layer.border_box;
        env->CallVoidMethod(view_, draw_gradient_method_, reinterpret_cast<jobject>(hdc), type,
                            px(box.left()), px(box.top()), px(box.right()), px(box.bottom()),
                            p1, p2, p3, p4, color_array, offset_array);
        env->DeleteLocalRef(color_array);
        env->DeleteLocalRef(offset_array);
        clear_exception(env);
    }

    JavaVM* vm_ = nullptr;
    jobject view_ = nullptr;
    jclass view_class_ = nullptr;
    jobject active_canvas_ = nullptr;
    float density_ = 1.0F;
    int default_font_size_ = 14;
    int viewport_width_ = 1;
    int document_height_ = 1;
    std::string base_url_;
    std::recursive_mutex mutex_;
    std::unordered_map<std::string, std::pair<int, int>> image_sizes_;
    litehtml::document::ptr document_;
    std::shared_ptr<DetailsElement> active_details_;

    jmethodID create_font_method_ = nullptr;
    jmethodID delete_font_method_ = nullptr;
    jmethodID font_metrics_method_ = nullptr;
    jmethodID text_width_method_ = nullptr;
    jmethodID draw_text_method_ = nullptr;
    jmethodID draw_rect_method_ = nullptr;
    jmethodID draw_image_method_ = nullptr;
    jmethodID draw_gradient_method_ = nullptr;
    jmethodID save_clip_method_ = nullptr;
    jmethodID restore_clip_method_ = nullptr;
    jmethodID request_image_method_ = nullptr;
    jmethodID dispatch_link_method_ = nullptr;
    jmethodID dispatch_image_method_ = nullptr;
};

AndroidDocumentContainer* renderer(jlong handle) {
    return reinterpret_cast<AndroidDocumentContainer*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeCreate(
    JNIEnv* env, jobject view, jstring html, jstring css, jfloat density, jint default_font_size) {
    try {
        auto* result = new AndroidDocumentContainer(env, view, density, default_font_size);
        if (!result->create_document(from_jstring(env, html), from_jstring(env, css))) {
            delete result;
            return 0;
        }
        return reinterpret_cast<jlong>(result);
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "create failed: %s", error.what());
        return 0;
    } catch (...) {
        log_error("create failed with unknown exception");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeLayout(
    JNIEnv*, jobject, jlong handle, jint width) {
    try {
        return handle == 0 ? 0 : renderer(handle)->layout(width);
    } catch (...) {
        log_error("layout failed");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeDraw(
    JNIEnv*, jobject, jlong handle, jobject canvas, jfloat tile_top, jfloat tile_height) {
    if (handle == 0 || canvas == nullptr) return;
    try {
        auto* value = renderer(handle);
        value->begin_draw(canvas);
        value->draw(canvas, tile_top, tile_height);
        value->end_draw();
    } catch (...) {
        renderer(handle)->end_draw();
        log_error("draw failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeSetImageSize(
    JNIEnv* env, jobject, jlong handle, jstring url, jint width, jint height) {
    if (handle == 0) return;
    renderer(handle)->set_image_size(from_jstring(env, url), width, height);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeHitTest(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y) {
    return handle == 0 ? 0 : renderer(handle)->hit_test(x, y);
}

extern "C" JNIEXPORT void JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativePointerDown(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y) {
    if (handle != 0) renderer(handle)->pointer_down(x, y);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativePointerUp(
    JNIEnv*, jobject, jlong handle, jfloat x, jfloat y) {
    return handle != 0 && renderer(handle)->pointer_up(x, y) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativePointerCancel(
    JNIEnv*, jobject, jlong handle) {
    if (handle != 0) renderer(handle)->pointer_cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_juhao_murexide_ui_components_litehtml_LiteHtmlView_nativeDestroy(
    JNIEnv*, jobject, jlong handle) {
    delete renderer(handle);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}
