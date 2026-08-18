/**
 * Lightweight i18n (no library): dictionaries for the chrome and the booking
 * journey's high-traffic strings. The chosen language persists in
 * localStorage and switching applies IN PLACE (no reload): the locale store
 * below notifies App's root subscription and the tree re-renders, so every
 * t() call site renders in the new language without losing any state.
 *
 * Deep content (fare rules, long help copy) stays English for now; the keys
 * below cover what a traveller touches on every visit.
 */

import { useSyncExternalStore } from 'react';

export const LANGUAGES = [
  { code: 'en', name: 'English' },
  { code: 'es', name: 'Español' },
  { code: 'fr', name: 'Français' },
  { code: 'de', name: 'Deutsch' },
  { code: 'zh', name: '中文' },
  { code: 'ja', name: '日本語' },
  { code: 'ar', name: 'العربية' },
  { code: 'ru', name: 'Русский' },
  { code: 'hi', name: 'हिन्दी' },
  { code: 'te', name: 'తెలుగు' },
] as const;

export type LanguageCode = (typeof LANGUAGES)[number]['code'];

const LANG_KEY = 'skybook.lang';

type Dict = Record<string, string>;

const en: Dict = {
  'nav.search': 'Search flights',
  'nav.trips': 'My trips',
  'nav.profile': 'Profile',
  'nav.signin': 'Sign in',
  'nav.signout': 'Sign out',
  'hero.titleLead': 'Where would you like to',
  'hero.titleAccent': 'fly?',
  'hero.sub':
    'Search a year of departures across 29 airports on three continents, pick your seat from the actual cabin, and carry a boarding pass you can scan. No account needed to look.',
  'widget.roundtrip': 'Round trip',
  'widget.oneway': 'One way',
  'widget.multicity': 'Multi-city',
  'widget.from': 'From',
  'widget.to': 'To',
  'widget.when': 'Travelling when?',
  'widget.depart': 'Depart',
  'widget.return': 'Return',
  'widget.guestsCabin': 'Guests and Cabin',
  'widget.search': 'Search',
  'stepper.flights': 'Flights',
  'stepper.guests': 'Guests',
  'stepper.seats': 'Seats',
  'stepper.bags': 'Bags',
  'stepper.payment': 'Payment',
  'stepper.back': 'Back',
  'cta.select': 'Select',
  'cta.continue': 'Continue',
  'cta.paynow': 'Pay now',
  'cta.done': 'Done',
  'payment.chargedIn': 'You will be charged in pounds sterling ({amount}); prices shown in other currencies are approximate.',
};

const es: Dict = {
  'nav.search': 'Buscar vuelos',
  'nav.trips': 'Mis viajes',
  'nav.profile': 'Perfil',
  'nav.signin': 'Iniciar sesión',
  'nav.signout': 'Cerrar sesión',
  'hero.titleLead': '¿A dónde te gustaría',
  'hero.titleAccent': 'volar?',
  'hero.sub':
    'Busca un año de salidas entre 29 aeropuertos, elige tu asiento en la cabina real y lleva una tarjeta de embarque escaneable. No necesitas cuenta para mirar.',
  'widget.roundtrip': 'Ida y vuelta',
  'widget.oneway': 'Solo ida',
  'widget.multicity': 'Multidestino',
  'widget.from': 'Origen',
  'widget.to': 'Destino',
  'widget.when': '¿Cuándo viajas?',
  'widget.depart': 'Ida',
  'widget.return': 'Vuelta',
  'widget.guestsCabin': 'Pasajeros y cabina',
  'widget.search': 'Buscar',
  'stepper.flights': 'Vuelos',
  'stepper.guests': 'Pasajeros',
  'stepper.seats': 'Asientos',
  'stepper.bags': 'Equipaje',
  'stepper.payment': 'Pago',
  'stepper.back': 'Atrás',
  'cta.select': 'Elegir',
  'cta.continue': 'Continuar',
  'cta.paynow': 'Pagar ahora',
  'cta.done': 'Listo',
  'payment.chargedIn': 'Se te cobrará en libras esterlinas ({amount}); los precios en otras monedas son aproximados.',
};

const fr: Dict = {
  'nav.search': 'Rechercher des vols',
  'nav.trips': 'Mes voyages',
  'nav.profile': 'Profil',
  'nav.signin': 'Se connecter',
  'nav.signout': 'Se déconnecter',
  'hero.titleLead': 'Où aimeriez-vous',
  'hero.titleAccent': 'voler ?',
  'hero.sub':
    'Explorez un an de départs entre 29 aéroports, choisissez votre siège dans la vraie cabine et emportez une carte d’embarquement scannable. Aucun compte requis pour regarder.',
  'widget.roundtrip': 'Aller-retour',
  'widget.oneway': 'Aller simple',
  'widget.multicity': 'Multi-destinations',
  'widget.from': 'Départ',
  'widget.to': 'Arrivée',
  'widget.when': 'Quand partez-vous ?',
  'widget.depart': 'Aller',
  'widget.return': 'Retour',
  'widget.guestsCabin': 'Passagers et cabine',
  'widget.search': 'Rechercher',
  'stepper.flights': 'Vols',
  'stepper.guests': 'Passagers',
  'stepper.seats': 'Sièges',
  'stepper.bags': 'Bagages',
  'stepper.payment': 'Paiement',
  'stepper.back': 'Retour',
  'cta.select': 'Choisir',
  'cta.continue': 'Continuer',
  'cta.paynow': 'Payer',
  'cta.done': 'Terminé',
  'payment.chargedIn': 'Le débit sera effectué en livres sterling ({amount}) ; les prix affichés dans d’autres devises sont approximatifs.',
};

const de: Dict = {
  'nav.search': 'Flüge suchen',
  'nav.trips': 'Meine Reisen',
  'nav.profile': 'Profil',
  'nav.signin': 'Anmelden',
  'nav.signout': 'Abmelden',
  'hero.titleLead': 'Wohin möchten Sie',
  'hero.titleAccent': 'fliegen?',
  'hero.sub':
    'Durchsuchen Sie ein Jahr an Abflügen zwischen 29 Flughäfen, wählen Sie Ihren Sitz in der echten Kabine und erhalten Sie eine scannbare Bordkarte. Zum Stöbern ist kein Konto nötig.',
  'widget.roundtrip': 'Hin- und Rückflug',
  'widget.oneway': 'Nur Hinflug',
  'widget.multicity': 'Gabelflug',
  'widget.from': 'Von',
  'widget.to': 'Nach',
  'widget.when': 'Wann reisen Sie?',
  'widget.depart': 'Hinflug',
  'widget.return': 'Rückflug',
  'widget.guestsCabin': 'Reisende und Kabine',
  'widget.search': 'Suchen',
  'stepper.flights': 'Flüge',
  'stepper.guests': 'Reisende',
  'stepper.seats': 'Sitzplätze',
  'stepper.bags': 'Gepäck',
  'stepper.payment': 'Zahlung',
  'stepper.back': 'Zurück',
  'cta.select': 'Auswählen',
  'cta.continue': 'Weiter',
  'cta.paynow': 'Jetzt zahlen',
  'cta.done': 'Fertig',
  'payment.chargedIn': 'Die Abbuchung erfolgt in Pfund Sterling ({amount}); Preise in anderen Währungen sind Näherungswerte.',
};

const zh: Dict = {
  'nav.search': '搜索航班',
  'nav.trips': '我的行程',
  'nav.profile': '个人资料',
  'nav.signin': '登录',
  'nav.signout': '退出登录',
  'hero.titleLead': '您想飞往',
  'hero.titleAccent': '哪里？',
  'hero.sub': '搜索 29 个机场之间全年的航班，在真实客舱中挑选座位，并获得可扫描的登机牌。浏览无需注册账户。',
  'widget.roundtrip': '往返',
  'widget.oneway': '单程',
  'widget.multicity': '多城市',
  'widget.from': '出发地',
  'widget.to': '目的地',
  'widget.when': '出行日期',
  'widget.depart': '去程',
  'widget.return': '返程',
  'widget.guestsCabin': '乘客与舱位',
  'widget.search': '搜索',
  'stepper.flights': '航班',
  'stepper.guests': '乘客',
  'stepper.seats': '座位',
  'stepper.bags': '行李',
  'stepper.payment': '支付',
  'stepper.back': '返回',
  'cta.select': '选择',
  'cta.continue': '继续',
  'cta.paynow': '立即支付',
  'cta.done': '完成',
  'payment.chargedIn': '将以英镑（{amount}）扣款；其他货币显示的价格仅供参考。',
};

const ja: Dict = {
  'nav.search': 'フライト検索',
  'nav.trips': 'マイ旅程',
  'nav.profile': 'プロフィール',
  'nav.signin': 'ログイン',
  'nav.signout': 'ログアウト',
  'hero.titleLead': 'どちらへ',
  'hero.titleAccent': '飛びますか？',
  'hero.sub': '30路線・1年分の出発便を検索し、実際のキャビンから座席を選び、スキャンできる搭乗券を受け取れます。閲覧にアカウントは不要です。',
  'widget.roundtrip': '往復',
  'widget.oneway': '片道',
  'widget.multicity': '周遊',
  'widget.from': '出発地',
  'widget.to': '到着地',
  'widget.when': 'ご出発日',
  'widget.depart': '往路',
  'widget.return': '復路',
  'widget.guestsCabin': '人数と座席クラス',
  'widget.search': '検索',
  'stepper.flights': 'フライト',
  'stepper.guests': '搭乗者',
  'stepper.seats': '座席',
  'stepper.bags': '手荷物',
  'stepper.payment': 'お支払い',
  'stepper.back': '戻る',
  'cta.select': '選択',
  'cta.continue': '次へ',
  'cta.paynow': '支払う',
  'cta.done': '完了',
  'payment.chargedIn': 'ご請求は英ポンド（{amount}）で行われます。他通貨の表示価格は目安です。',
};

const ar: Dict = {
  'nav.search': 'البحث عن رحلات',
  'nav.trips': 'رحلاتي',
  'nav.profile': 'الملف الشخصي',
  'nav.signin': 'تسجيل الدخول',
  'nav.signout': 'تسجيل الخروج',
  'hero.titleLead': 'إلى أين تودّ',
  'hero.titleAccent': 'أن تسافر؟',
  'hero.sub':
    'ابحث في رحلات عام كامل بين 29 مطارًا، واختر مقعدك من المقصورة الحقيقية، واحصل على بطاقة صعود قابلة للمسح. لا حاجة لحساب للتصفح.',
  'widget.roundtrip': 'ذهاب وعودة',
  'widget.oneway': 'ذهاب فقط',
  'widget.multicity': 'وجهات متعددة',
  'widget.from': 'من',
  'widget.to': 'إلى',
  'widget.when': 'متى السفر؟',
  'widget.depart': 'الذهاب',
  'widget.return': 'العودة',
  'widget.guestsCabin': 'المسافرون والدرجة',
  'widget.search': 'بحث',
  'stepper.flights': 'الرحلات',
  'stepper.guests': 'المسافرون',
  'stepper.seats': 'المقاعد',
  'stepper.bags': 'الأمتعة',
  'stepper.payment': 'الدفع',
  'stepper.back': 'رجوع',
  'cta.select': 'اختيار',
  'cta.continue': 'متابعة',
  'cta.paynow': 'ادفع الآن',
  'cta.done': 'تم',
  'payment.chargedIn': 'سيتم الخصم بالجنيه الإسترليني ({amount})؛ الأسعار المعروضة بعملات أخرى تقريبية.',
};

const ru: Dict = {
  'nav.search': 'Поиск рейсов',
  'nav.trips': 'Мои поездки',
  'nav.profile': 'Профиль',
  'nav.signin': 'Войти',
  'nav.signout': 'Выйти',
  'hero.titleLead': 'Куда бы вы хотели',
  'hero.titleAccent': 'полететь?',
  'hero.sub':
    'Ищите рейсы на год вперёд между 29 аэропортами, выбирайте место в реальном салоне и получайте посадочный талон со сканируемым кодом. Для просмотра аккаунт не нужен.',
  'widget.roundtrip': 'Туда и обратно',
  'widget.oneway': 'В одну сторону',
  'widget.multicity': 'Сложный маршрут',
  'widget.from': 'Откуда',
  'widget.to': 'Куда',
  'widget.when': 'Когда летите?',
  'widget.depart': 'Туда',
  'widget.return': 'Обратно',
  'widget.guestsCabin': 'Пассажиры и класс',
  'widget.search': 'Найти',
  'stepper.flights': 'Рейсы',
  'stepper.guests': 'Пассажиры',
  'stepper.seats': 'Места',
  'stepper.bags': 'Багаж',
  'stepper.payment': 'Оплата',
  'stepper.back': 'Назад',
  'cta.select': 'Выбрать',
  'cta.continue': 'Далее',
  'cta.paynow': 'Оплатить',
  'cta.done': 'Готово',
  'payment.chargedIn': 'Списание произойдёт в фунтах стерлингов ({amount}); цены в других валютах приблизительны.',
};

const hi: Dict = {
  'nav.search': 'उड़ानें खोजें',
  'nav.trips': 'मेरी यात्राएँ',
  'nav.profile': 'प्रोफ़ाइल',
  'nav.signin': 'साइन इन',
  'nav.signout': 'साइन आउट',
  'hero.titleLead': 'आप कहाँ',
  'hero.titleAccent': 'उड़ना चाहेंगे?',
  'hero.sub':
    '29 हवाई अड्डों के बीच साल भर की उड़ानें खोजें, असली केबिन से अपनी सीट चुनें, और स्कैन होने वाला बोर्डिंग पास पाएँ। देखने के लिए खाते की ज़रूरत नहीं।',
  'widget.roundtrip': 'राउंड ट्रिप',
  'widget.oneway': 'एक तरफ़ा',
  'widget.multicity': 'मल्टी-सिटी',
  'widget.from': 'कहाँ से',
  'widget.to': 'कहाँ तक',
  'widget.when': 'कब यात्रा करेंगे?',
  'widget.depart': 'प्रस्थान',
  'widget.return': 'वापसी',
  'widget.guestsCabin': 'यात्री और केबिन',
  'widget.search': 'खोजें',
  'stepper.flights': 'उड़ानें',
  'stepper.guests': 'यात्री',
  'stepper.seats': 'सीटें',
  'stepper.bags': 'सामान',
  'stepper.payment': 'भुगतान',
  'stepper.back': 'वापस',
  'cta.select': 'चुनें',
  'cta.continue': 'आगे बढ़ें',
  'cta.paynow': 'अभी भुगतान करें',
  'cta.done': 'हो गया',
  'payment.chargedIn': 'भुगतान पाउंड स्टर्लिंग ({amount}) में लिया जाएगा; अन्य मुद्राओं में दिखाए गए दाम अनुमानित हैं।',
};

const te: Dict = {
  'nav.search': 'విమానాలు వెతకండి',
  'nav.trips': 'నా ప్రయాణాలు',
  'nav.profile': 'ప్రొఫైల్',
  'nav.signin': 'సైన్ ఇన్',
  'nav.signout': 'సైన్ అవుట్',
  'hero.titleLead': 'మీరు ఎక్కడికి',
  'hero.titleAccent': 'ఎగరాలనుకుంటున్నారు?',
  'hero.sub':
    '29 విమానాశ్రయాల మధ్య ఏడాది పొడవునా విమానాలను వెతకండి, నిజమైన క్యాబిన్ నుంచి మీ సీటు ఎంచుకోండి, స్కాన్ చేయగల బోర్డింగ్ పాస్ పొందండి. చూడటానికి ఖాతా అవసరం లేదు.',
  'widget.roundtrip': 'రౌండ్ ట్రిప్',
  'widget.oneway': 'ఒకవైపు',
  'widget.multicity': 'మల్టీ-సిటీ',
  'widget.from': 'ఎక్కడి నుంచి',
  'widget.to': 'ఎక్కడికి',
  'widget.when': 'ఎప్పుడు ప్రయాణం?',
  'widget.depart': 'వెళ్లే ప్రయాణం',
  'widget.return': 'తిరుగు ప్రయాణం',
  'widget.guestsCabin': 'ప్రయాణికులు & క్యాబిన్',
  'widget.search': 'వెతకండి',
  'stepper.flights': 'విమానాలు',
  'stepper.guests': 'ప్రయాణికులు',
  'stepper.seats': 'సీట్లు',
  'stepper.bags': 'లగేజీ',
  'stepper.payment': 'చెల్లింపు',
  'stepper.back': 'వెనక్కి',
  'cta.select': 'ఎంచుకోండి',
  'cta.continue': 'కొనసాగించండి',
  'cta.paynow': 'ఇప్పుడు చెల్లించండి',
  'cta.done': 'పూర్తయింది',
  'payment.chargedIn': 'చెల్లింపు పౌండ్ స్టెర్లింగ్ ({amount})లో వసూలు చేయబడుతుంది; ఇతర కరెన్సీలలో చూపిన ధరలు సుమారు విలువలు.',
};

const DICTS: Record<LanguageCode, Dict> = { en, es, fr, de, zh, ja, ar, ru, hi, te };

function stored(): LanguageCode {
  try {
    const raw = localStorage.getItem(LANG_KEY);
    return (LANGUAGES.some((l) => l.code === raw) ? raw : 'en') as LanguageCode;
  } catch {
    return 'en';
  }
}

// ---------------------------------------------------------------------
// Live locale store. Language and display currency apply IN PLACE - no
// page reload: setters mutate here and notify, App's root subscribes via
// useLocale(), and the whole tree re-renders (never remounts, so journey
// state survives a mid-booking switch). t()/price() read live values.
// ---------------------------------------------------------------------

let activeLanguage: LanguageCode = stored();
let localeVersion = 0;
const localeListeners = new Set<() => void>();

if (typeof document !== 'undefined') {
  document.documentElement.lang = activeLanguage;
  document.documentElement.dir = activeLanguage === 'ar' ? 'rtl' : 'ltr';
}

/** The active language right now (live - do not cache across renders). */
export function currentLanguage(): LanguageCode {
  return activeLanguage;
}

/** Bump + notify subscribers; also used by setDisplayCurrency (format.ts). */
export function notifyLocaleChanged(): void {
  localeVersion += 1;
  localeListeners.forEach((listener) => listener());
}

/**
 * Subscribe a component to locale (language/currency) changes. Called once
 * at the App ROOT: its re-render cascades down the whole unmemoized tree,
 * re-evaluating every t() and price() in place.
 */
export function useLocale(): number {
  return useSyncExternalStore(
    (onChange) => {
      localeListeners.add(onChange);
      return () => localeListeners.delete(onChange);
    },
    () => localeVersion,
  );
}

export function t(key: string, params?: Record<string, string>): string {
  let text = DICTS[activeLanguage][key] ?? en[key] ?? key;
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      text = text.replace(`{${k}}`, v);
    }
  }
  return text;
}

export function setLanguage(code: LanguageCode): void {
  try {
    localStorage.setItem(LANG_KEY, code);
  } catch {
    // Private mode etc - the switch simply won't stick beyond this visit.
  }
  activeLanguage = code;
  document.documentElement.lang = code;
  // Arabic reads right-to-left: flipping the document direction makes the
  // whole layout mirror (flex/grid/text) - the honest way to serve ar, not
  // left-aligned Arabic text.
  document.documentElement.dir = code === 'ar' ? 'rtl' : 'ltr';
  notifyLocaleChanged();
}
