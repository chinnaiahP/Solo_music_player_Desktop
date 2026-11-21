#include <Windows.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/base.h>

using namespace winrt;
using namespace Windows::Media;

namespace {
SystemMediaTransportControls g_controls{nullptr};
event_token g_buttonToken{};
void(__cdecl* g_actionCallback)(int) = nullptr;
bool g_apartmentInitialized = false;
}

extern "C" __declspec(dllexport) int __cdecl SoloSmtcInitialize(
    HWND window,
    void(__cdecl* actionCallback)(int)) {
  DWORD windowProcessId = 0;
  if (!IsWindow(window)) return static_cast<int>(E_HANDLE);
  GetWindowThreadProcessId(window, &windowProcessId);
  if (windowProcessId != GetCurrentProcessId() || !actionCallback) {
    return static_cast<int>(E_ACCESSDENIED);
  }
  try {
    init_apartment(apartment_type::multi_threaded);
    g_apartmentInitialized = true;
    g_actionCallback = actionCallback;

    auto factory = get_activation_factory<SystemMediaTransportControls>();
    auto interop = factory.as<ISystemMediaTransportControlsInterop>();
    check_hresult(interop->GetForWindow(
        window,
        guid_of<SystemMediaTransportControls>(),
        put_abi(g_controls)));

    g_controls.IsEnabled(true);
    g_controls.IsPlayEnabled(true);
    g_controls.IsPauseEnabled(true);
    g_controls.IsNextEnabled(true);
    g_controls.IsPreviousEnabled(true);
    g_buttonToken = g_controls.ButtonPressed(
        [](SystemMediaTransportControls const&,
           SystemMediaTransportControlsButtonPressedEventArgs const& args) {
          if (!g_actionCallback) return;
          int action = 0;
          switch (args.Button()) {
            case SystemMediaTransportControlsButton::Play: action = 1; break;
            case SystemMediaTransportControlsButton::Pause: action = 2; break;
            case SystemMediaTransportControlsButton::Next: action = 3; break;
            case SystemMediaTransportControlsButton::Previous: action = 4; break;
            default: return;
          }
          g_actionCallback(action);
        });
    return 0;
  } catch (hresult_error const& error) {
    g_actionCallback = nullptr;
    if (g_controls) g_controls = nullptr;
    if (g_apartmentInitialized) { uninit_apartment(); g_apartmentInitialized = false; }
    return error.code().value;
  } catch (...) {
    g_actionCallback = nullptr;
    if (g_controls) g_controls = nullptr;
    if (g_apartmentInitialized) { uninit_apartment(); g_apartmentInitialized = false; }
    return static_cast<int>(E_FAIL);
  }
}

extern "C" __declspec(dllexport) int __cdecl SoloSmtcSetState(
    int state,
    wchar_t const* title,
    wchar_t const* artist) {
  try {
    if (!g_controls) return static_cast<int>(E_HANDLE);
    g_controls.PlaybackStatus(state == 1 ? MediaPlaybackStatus::Playing
                                        : MediaPlaybackStatus::Paused);
    auto updater = g_controls.DisplayUpdater();
    updater.Type(MediaPlaybackType::Music);
    updater.MusicProperties().Title(title ? title : L"Solo Music Player");
    updater.MusicProperties().Artist(artist ? artist : L"");
    updater.Update();
    return 0;
  } catch (hresult_error const& error) {
    return error.code().value;
  } catch (...) {
    return static_cast<int>(E_FAIL);
  }
}

extern "C" __declspec(dllexport) int __cdecl SoloSmtcShutdown() {
  try {
    if (g_controls) {
      g_controls.ButtonPressed(g_buttonToken);
      g_controls.PlaybackStatus(MediaPlaybackStatus::Closed);
      g_controls.IsEnabled(false);
      g_controls = nullptr;
    }
    g_actionCallback = nullptr;
    if (g_apartmentInitialized) {
      uninit_apartment();
      g_apartmentInitialized = false;
    }
    return 0;
  } catch (...) {
    return static_cast<int>(E_FAIL);
  }
}
