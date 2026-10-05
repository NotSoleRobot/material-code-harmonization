import i18n from "i18next";
import { initReactI18next } from "react-i18next";

export const resources = {
  en: {
    translation: {
      nav: {
        dashboard: "Dashboard",
        myMaterials: "My Materials",
        catalog: "Catalog",
        ingest: "Ingestion",
        review: "Review Queue",
        compare: "Comparison",
        audit: "Audit Trail",
        adminUsers: "Users",
        logout: "Sign Out",
      },
      roles: {
        OPERATOR: "CPSE Operator",
        SENIOR_REVIEWER: "Senior Reviewer",
        ADMIN: "Central Administrator",
      },
      auth: {
        email: "Email Address",
        password: "Password",
        signInBtn: "Sign In to Portal",
        signingIn: "Authenticating…",
        invalidCreds: "Invalid email or password.",
      },
      dashboard: {
        title: "Dashboard",
        subtitle: "A clear overview of materials, matching progress, and items that need attention.",
        totalMaterials: "Materials",
        totalGroups: "Harmonized Groups",
        dedupRate: "Deduplication Rate",
        dedupFormula: "Duplicates Eliminated / Materials Reviewed",
        pendingReview: "Awaiting Review",
        confirmedMappings: "Confirmed Mappings",
        rejectedMappings: "Rejected Items",
        unmatchedItems: "Unmatched Pool",
        batchHarmonizeBtn: "Run Batch Harmonization",
      },
      catalog: {
        searchTitle: "Catalog",
        searchSub: "Search existing materials and codes before submitting a new request.",
        unifiedTitle: "Unified Catalog",
        unifiedSub: "Browse harmonized material groups and their source records.",
        searchPlaceholder: "Search by catalog reference, description, standard, or enterprise material code…",
        allCategories: "All Categories",
        exportBtn: "Export",
        requestCodeBtn: "Submit material",
        emptySearch: "No matching material records were found.",
        referenceBadge: "Catalog Reference",
        viewDetails: "View Details",
      },
      review: {
        confHigh: "High Confidence",
        confMid: "Medium Confidence",
        confLow: "Low Confidence",
      },
      common: {
        error: "An error occurred",
        retry: "Retry",
        cancel: "Cancel",
        copyCode: "Copy Code",
        copied: "Copied",
      },
    },
  },
};

i18n
  .use(initReactI18next)
  .init({
    resources,
    lng: "en",
    fallbackLng: "en",
    interpolation: { escapeValue: false },
  });

export default i18n;
